package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetHostDiskUsageUseCase;
import net.vaier.application.NotifyAdminsOfDiskPressureUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.DiskPressureTracker;
import net.vaier.domain.DiskUsage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls host disk free space and emails admins when it crosses the configured fullness threshold.
 * Mirrors {@link PeerConnectivityWatcher}: an in-memory {@link DiskPressureTracker} avoids
 * re-alerting every poll, and the first observation after startup is a baseline so a restart never
 * produces noise. Notifies on both transitions — into pressure and back to normal.
 */
@Component
@Slf4j
public class DiskUsageWatcher {

    private final GetHostDiskUsageUseCase diskUsage;
    private final NotifyAdminsOfDiskPressureUseCase notifier;
    private final ConfigResolver configResolver;
    private final DiskPressureTracker tracker = new DiskPressureTracker();

    public DiskUsageWatcher(GetHostDiskUsageUseCase diskUsage,
                            NotifyAdminsOfDiskPressureUseCase notifier,
                            ConfigResolver configResolver) {
        this.diskUsage = diskUsage;
        this.notifier = notifier;
        this.configResolver = configResolver;
    }

    @Scheduled(fixedDelay = 60000)
    public void checkDiskUsage() {
        try {
            int threshold = configResolver.getDiskMonitorThresholdPercent();
            DiskUsage usage = diskUsage.getHostDiskUsage();
            switch (tracker.update(usage.isAbove(threshold))) {
                case CROSSED_ABOVE -> notifier.notifyAdminsOfDiskPressure(usage, threshold);
                case CROSSED_BELOW -> notifier.notifyAdminsOfDiskRecovery(usage, threshold);
                case NONE -> { /* no boundary crossed; stay quiet */ }
            }
        } catch (Exception e) {
            log.debug("Host disk usage check failed: {}", e.getMessage());
        }
    }
}
