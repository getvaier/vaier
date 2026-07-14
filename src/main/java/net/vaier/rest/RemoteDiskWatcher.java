package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.NotifyAdminsOfDiskFillForecastUseCase;
import net.vaier.application.NotifyAdminsOfRemoteDiskPressureUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.CommandResult;
import net.vaier.domain.Machine;
import net.vaier.domain.RemoteDiskForecastTracker;
import net.vaier.domain.RemoteDiskPressureTracker;
import net.vaier.domain.RemoteDiskUsage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/**
 * Polls the root-filesystem fullness of every SSH-accessible machine that Vaier holds a credential for
 * and emails admins when one crosses the disk-alert threshold. This covers the Vaier host itself
 * (via SSH-to-self) as well as every other machine, so there is a single disk-alert path for the
 * whole fleet. It runs a bounded {@code df} over SSH via {@link RunRemoteCommandUseCase} (never
 * touching the SSH ports directly), and a per-machine {@link RemoteDiskPressureTracker} makes it
 * alert only on threshold crossings — into pressure and back to normal — so no machine is
 * re-alerted every poll and a restart never produces noise.
 *
 * <p>Machines without SSH access or without a stored credential are skipped silently, so Vaier never
 * mounts a failed-auth storm. A machine that is unreachable, whose {@code df} times out or exits
 * non-zero, or returns output that cannot be parsed is degraded, not alarmed — the tracker is left
 * untouched so a transient failure can never masquerade as a full disk.
 *
 * <p>The same {@code df} reading feeds a second, forward-looking consumer: a per-machine
 * {@link RemoteDiskForecastTracker} projects the disk-fill <b>runway</b> from the recent trend and emails
 * an early warning when a disk is projected to fill within the forecast horizon <em>while still below</em>
 * the disk-pressure threshold — so a filling disk pages once as a forecast and then the level alert takes
 * over. No extra SSH round-trip: the forecast reads the reading the level check already took, and a failed
 * or unparseable {@code df} records no sample (the failure paths {@code return} before the forecast feed).
 */
@Component
@Slf4j
public class RemoteDiskWatcher {

    /**
     * The command a disk reading is taken with. It lives on {@link RemoteDiskUsage#DF_COMMAND}, next to the
     * parser that reads it: the scheduled watcher and the Explorer's on-demand read (#323) share it, so the
     * alert email and the tree can never end up measuring two different things.
     */
    static final String DF_COMMAND = RemoteDiskUsage.DF_COMMAND;

    private final GetMachinesUseCase machines;
    private final GetHostCredentialUseCase credentials;
    private final RunRemoteCommandUseCase remoteCommand;
    private final NotifyAdminsOfRemoteDiskPressureUseCase notifier;
    private final NotifyAdminsOfDiskFillForecastUseCase forecastNotifier;
    private final ConfigResolver configResolver;
    private final Clock clock;
    private final RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();
    private final RemoteDiskForecastTracker forecastTracker = new RemoteDiskForecastTracker();

    public RemoteDiskWatcher(GetMachinesUseCase machines,
                             GetHostCredentialUseCase credentials,
                             RunRemoteCommandUseCase remoteCommand,
                             NotifyAdminsOfRemoteDiskPressureUseCase notifier,
                             NotifyAdminsOfDiskFillForecastUseCase forecastNotifier,
                             ConfigResolver configResolver,
                             Clock clock) {
        this.machines = machines;
        this.credentials = credentials;
        this.remoteCommand = remoteCommand;
        this.notifier = notifier;
        this.forecastNotifier = forecastNotifier;
        this.configResolver = configResolver;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 300000)
    public void checkRemoteDiskUsage() {
        int threshold = configResolver.getDiskMonitorThresholdPercent();
        for (Machine machine : machines.getAllMachines()) {
            checkMachine(machine, threshold);
        }
    }

    private void checkMachine(Machine machine, int threshold) {
        if (!machine.effectiveSshAccess()) {
            return;
        }
        if (credentials.getHostCredential(machine.name()).isEmpty()) {
            return;
        }
        try {
            CommandResult result = remoteCommand.run(machine.name(), DF_COMMAND);
            if (result.timedOut() || result.exitCode() != 0) {
                log.debug("Remote df on {} failed (exit={}, timedOut={}); skipping",
                        machine.name(), result.exitCode(), result.timedOut());
                return;
            }
            Optional<RemoteDiskUsage> usage = RemoteDiskUsage.parse(machine.name(), result.stdout());
            if (usage.isEmpty()) {
                log.debug("Could not parse remote df output from {}; skipping", machine.name());
                return;
            }
            switch (tracker.update(machine.name(), usage.get().isAbove(threshold))) {
                case CROSSED_ABOVE -> notifier.notifyAdminsOfRemoteDiskPressure(usage.get(), threshold);
                case CROSSED_BELOW -> notifier.notifyAdminsOfRemoteDiskRecovery(usage.get(), threshold);
                case NONE -> { /* no boundary crossed; stay quiet */ }
            }

            // Feed the same reading to the trend/forecast consumer (no extra SSH round-trip). The tracker
            // decides what to say: an early warning on the way in, and — only on a genuine recovery below
            // the threshold — an all-clear; a hand-off to the disk-pressure alert above yields neither.
            RemoteDiskForecastTracker.Observation forecast =
                forecastTracker.observe(machine.name(), clock.instant(), usage.get().usedPercent(), threshold);
            forecast.earlyWarning().ifPresent(forecastNotifier::notifyAdminsOfDiskFillForecast);
            forecast.cleared().ifPresent(forecastNotifier::notifyAdminsOfDiskFillForecastCleared);
        } catch (Exception e) {
            log.debug("Remote disk check failed for {}: {}", machine.name(), e.getMessage());
        }
    }
}
