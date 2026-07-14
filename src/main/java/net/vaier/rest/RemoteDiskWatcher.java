package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetDiskWatchesUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.NotifyAdminsOfDiskFillForecastUseCase;
import net.vaier.application.NotifyAdminsOfRemoteDiskPressureUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DiskWatch;
import net.vaier.domain.DiskWatches;
import net.vaier.domain.Machine;
import net.vaier.domain.RemoteDiskForecastTracker;
import net.vaier.domain.RemoteDiskPressureTracker;
import net.vaier.domain.RemoteDiskUsage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Polls <b>every real filesystem</b> of every SSH-accessible machine that Vaier holds a credential for and
 * emails admins when a watched one crosses its alert threshold. This covers the Vaier host itself (via
 * SSH-to-self) as well as every other machine, so there is a single disk-alert path for the whole fleet. It
 * runs a bounded {@code df} over SSH via {@link RunRemoteCommandUseCase} (never touching the SSH ports
 * directly), and a per-filesystem {@link RemoteDiskPressureTracker} makes it alert only on threshold
 * crossings — into pressure and back to normal — so nothing is re-alerted every poll and a restart never
 * produces noise.
 *
 * <p><b>#325.</b> It used to read {@code df -P /} — the root filesystem, and only the root filesystem. On the
 * NAS that is the fixed-size 2.3 GB DSM system partition, 88% by design and never moving, so the alert Vaier
 * could send was about a partition the operator could not act on, while {@code /volume1} — 11.6 TB, holding
 * every borg backup — was invisible and could have filled to 100% without a word. Now every filesystem is
 * read, each is judged against its own {@link DiskWatch} (watched or muted, at its own threshold or the
 * global one), and the alert <em>names the mount and its size</em>: "NAS /volume1 is at 91% (10.8 TiB, 1.0
 * TiB free)" tells the operator something they can act on; "NAS is at 88%" told them nothing.
 *
 * <p>Machines without SSH access or without a stored credential are skipped silently, so Vaier never mounts a
 * failed-auth storm. A machine that is unreachable, whose {@code df} times out or exits non-zero, or returns
 * output that cannot be parsed is degraded, not alarmed — the trackers are left untouched so a transient
 * failure can never masquerade as a full disk.
 *
 * <p>The same readings feed a second, forward-looking consumer: a per-filesystem
 * {@link RemoteDiskForecastTracker} projects the disk-fill <b>runway</b> from the recent trend and emails an
 * early warning when a filesystem is projected to fill within the forecast horizon <em>while still below</em>
 * its threshold — so a filling disk pages once as a forecast and then the level alert takes over. No extra
 * SSH round-trip: the forecast reads the readings the level check already took, and a failed or unparseable
 * {@code df} records no sample (the failure paths {@code return} before the forecast feed).
 */
@Component
@Slf4j
public class RemoteDiskWatcher {

    private final GetMachinesUseCase machines;
    private final GetHostCredentialUseCase credentials;
    private final RunRemoteCommandUseCase remoteCommand;
    private final NotifyAdminsOfRemoteDiskPressureUseCase notifier;
    private final NotifyAdminsOfDiskFillForecastUseCase forecastNotifier;
    private final GetDiskWatchesUseCase diskWatches;
    private final ConfigResolver configResolver;
    private final Clock clock;
    private final RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();
    private final RemoteDiskForecastTracker forecastTracker = new RemoteDiskForecastTracker();

    public RemoteDiskWatcher(GetMachinesUseCase machines,
                             GetHostCredentialUseCase credentials,
                             RunRemoteCommandUseCase remoteCommand,
                             NotifyAdminsOfRemoteDiskPressureUseCase notifier,
                             NotifyAdminsOfDiskFillForecastUseCase forecastNotifier,
                             GetDiskWatchesUseCase diskWatches,
                             ConfigResolver configResolver,
                             Clock clock) {
        this.machines = machines;
        this.credentials = credentials;
        this.remoteCommand = remoteCommand;
        this.notifier = notifier;
        this.forecastNotifier = forecastNotifier;
        this.diskWatches = diskWatches;
        this.configResolver = configResolver;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 300000)
    public void checkRemoteDiskUsage() {
        int globalThreshold = configResolver.getDiskMonitorThresholdPercent();
        DiskWatches watches = diskWatches.getDiskWatches();
        for (Machine machine : machines.getAllMachines()) {
            checkMachine(machine, watches, globalThreshold);
        }
    }

    private void checkMachine(Machine machine, DiskWatches watches, int globalThreshold) {
        if (!machine.effectiveSshAccess()) {
            return;
        }
        if (credentials.getHostCredential(machine.name()).isEmpty()) {
            return;
        }
        try {
            CommandResult result = remoteCommand.run(machine.name(), RemoteDiskUsage.DF_COMMAND);
            if (result.timedOut() || result.exitCode() != 0) {
                log.debug("Remote df on {} failed (exit={}, timedOut={}); skipping",
                        machine.name(), result.exitCode(), result.timedOut());
                return;
            }
            List<RemoteDiskUsage> filesystems = RemoteDiskUsage.parseList(machine.name(), result.stdout());
            if (filesystems.isEmpty()) {
                log.debug("Could not parse remote df output from {}; skipping", machine.name());
                return;
            }
            for (RemoteDiskUsage filesystem : filesystems) {
                checkFilesystem(machine, filesystem, watches, globalThreshold);
            }
        } catch (Exception e) {
            log.debug("Remote disk check failed for {}: {}", machine.name(), e.getMessage());
        }
    }

    /**
     * One filesystem, judged against its own watch. The watcher decides nothing: it asks the domain once —
     * {@code RemoteDiskUsage.judge} resolves mute, the filesystem's own threshold and the global fallback —
     * and then only decides <em>whom to tell</em>. {@code MachineService.getDiskUsage} asks the very same
     * method to feed the Explorer, which is what guarantees the alert email and the tree can never disagree
     * about a disk.
     *
     * <p>A {@link RemoteDiskUsage.DiskVerdict#silent() silent} filesystem is skipped whole: no alert, and no
     * forecast either. That is the domain's rule, not a convenience here.
     */
    private void checkFilesystem(Machine machine, RemoteDiskUsage filesystem, DiskWatches watches,
                                 int globalThreshold) {
        DiskWatch watch = watches.forFilesystem(machine.name(), filesystem.mountPoint());
        RemoteDiskUsage.DiskVerdict verdict = filesystem.judge(watch, globalThreshold);
        if (verdict.silent()) {
            return;
        }
        int threshold = verdict.thresholdPercent();

        switch (tracker.update(machine.name(), filesystem.mountPoint(), verdict.breaching())) {
            case CROSSED_ABOVE -> notifier.notifyAdminsOfRemoteDiskPressure(filesystem, threshold);
            case CROSSED_BELOW -> notifier.notifyAdminsOfRemoteDiskRecovery(filesystem, threshold);
            case NONE -> { /* no boundary crossed; stay quiet */ }
        }

        // Feed the same reading to the trend/forecast consumer (no extra SSH round-trip). The tracker decides
        // what to say: an early warning on the way in, and — only on a genuine recovery below the threshold —
        // an all-clear; a hand-off to the disk-pressure alert above yields neither. The threshold handed in
        // is this filesystem's own, so the forecast hands off at exactly the level the pressure alert fires
        // at.
        RemoteDiskForecastTracker.Observation forecast = forecastTracker.observe(
            machine.name(), filesystem.mountPoint(), clock.instant(), filesystem.usedPercent(), threshold);
        forecast.earlyWarning().ifPresent(forecastNotifier::notifyAdminsOfDiskFillForecast);
        forecast.cleared().ifPresent(forecastNotifier::notifyAdminsOfDiskFillForecastCleared);
    }
}
