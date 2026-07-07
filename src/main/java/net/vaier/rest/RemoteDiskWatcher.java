package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.NotifyAdminsOfRemoteDiskPressureUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.CommandResult;
import net.vaier.domain.Machine;
import net.vaier.domain.RemoteDiskPressureTracker;
import net.vaier.domain.RemoteDiskUsage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Polls the root-filesystem fullness of every SSH-accessible machine that Vaier holds a credential for
 * and emails admins when one crosses the disk-alert threshold — the remote-host sibling of
 * {@link DiskUsageWatcher}, which watches only Vaier's own disk. It runs a bounded {@code df} over SSH
 * via {@link RunRemoteCommandUseCase} (never touching the SSH ports directly), and a per-machine
 * {@link RemoteDiskPressureTracker} makes it alert only on threshold crossings — into pressure and back
 * to normal — so no machine is re-alerted every poll and a restart never produces noise.
 *
 * <p>Machines without SSH access or without a stored credential are skipped silently, so Vaier never
 * mounts a failed-auth storm. A machine that is unreachable, whose {@code df} times out or exits
 * non-zero, or returns output that cannot be parsed is degraded, not alarmed — the tracker is left
 * untouched so a transient failure can never masquerade as a full disk.
 */
@Component
@Slf4j
public class RemoteDiskWatcher {

    /**
     * {@code df -P /} — POSIX ({@code -P}) output guarantees a single, non-wrapping data row with a
     * stable {@code Capacity} (Use%) column even for long device names, and scoping to {@code /} keeps
     * the result to the root filesystem so there is exactly one row to parse.
     */
    static final String DF_COMMAND = "df -P /";

    private final GetMachinesUseCase machines;
    private final GetHostCredentialUseCase credentials;
    private final RunRemoteCommandUseCase remoteCommand;
    private final NotifyAdminsOfRemoteDiskPressureUseCase notifier;
    private final ConfigResolver configResolver;
    private final RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();

    public RemoteDiskWatcher(GetMachinesUseCase machines,
                             GetHostCredentialUseCase credentials,
                             RunRemoteCommandUseCase remoteCommand,
                             NotifyAdminsOfRemoteDiskPressureUseCase notifier,
                             ConfigResolver configResolver) {
        this.machines = machines;
        this.credentials = credentials;
        this.remoteCommand = remoteCommand;
        this.notifier = notifier;
        this.configResolver = configResolver;
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
        } catch (Exception e) {
            log.debug("Remote disk check failed for {}: {}", machine.name(), e.getMessage());
        }
    }
}
