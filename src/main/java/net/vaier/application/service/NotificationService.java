package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.NotifyAdminsOfBackupFailureUseCase;
import net.vaier.application.NotifyAdminsOfBackupServerDownUseCase;
import net.vaier.application.NotifyAdminsOfDiskFillForecastUseCase;
import net.vaier.application.NotifyAdminsOfPeerTransitionUseCase;
import net.vaier.application.NotifyAdminsOfRemoteDiskPressureUseCase;
import net.vaier.application.NotifyAdminsOfUpdateAvailableUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupServer;
import net.vaier.domain.DiskFillForecast;
import net.vaier.domain.DiskFillForecastCleared;
import net.vaier.domain.ImageUpdateRollup;
import net.vaier.domain.RemoteDiskUsage;
import net.vaier.domain.PeerSnapshot;
import net.vaier.domain.port.ForProbingTcp.ProbeResult;
import net.vaier.domain.port.ForSendingAdminNotification;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class NotificationService implements
        NotifyAdminsOfPeerTransitionUseCase,
        NotifyAdminsOfRemoteDiskPressureUseCase,
        NotifyAdminsOfDiskFillForecastUseCase,
        NotifyAdminsOfBackupFailureUseCase,
        NotifyAdminsOfBackupServerDownUseCase,
        NotifyAdminsOfUpdateAvailableUseCase {

    private final ForSendingAdminNotification adminNotifier;
    private final ConfigResolver configResolver;

    public NotificationService(ForSendingAdminNotification adminNotifier,
                               ConfigResolver configResolver) {
        this.adminNotifier = adminNotifier;
        this.configResolver = configResolver;
    }

    @Override
    public void notifyAdmins(PeerSnapshot snapshot) {
        adminNotifier.sendToAdmins(snapshot.notificationSubject(),
                snapshot.notificationBody(configResolver.getDomain()),
                "peer " + snapshot.name());
    }

    @Override
    public void notifyAdminsOfRemoteDiskPressure(RemoteDiskUsage usage, int thresholdPercent) {
        adminNotifier.sendToAdmins(usage.pressureSubject(),
                usage.pressureBody(thresholdPercent, configResolver.getDomain()),
                "remote disk pressure on " + usage.machineName());
    }

    @Override
    public void notifyAdminsOfRemoteDiskRecovery(RemoteDiskUsage usage, int thresholdPercent) {
        adminNotifier.sendToAdmins(usage.recoverySubject(),
                usage.pressureBody(thresholdPercent, configResolver.getDomain()),
                "remote disk recovery on " + usage.machineName());
    }

    @Override
    public void notifyAdminsOfDiskFillForecast(DiskFillForecast forecast) {
        adminNotifier.sendToAdmins(forecast.forecastSubject(),
                forecast.forecastBody(configResolver.getDomain()),
                "disk-fill forecast on " + forecast.machineName());
    }

    @Override
    public void notifyAdminsOfDiskFillForecastCleared(DiskFillForecastCleared cleared) {
        adminNotifier.sendToAdmins(cleared.clearedSubject(),
                cleared.clearedBody(configResolver.getDomain()),
                "disk-fill forecast cleared on " + cleared.machineName());
    }

    @Override
    public void notifyAdminsOfBackupFailure(BackupRun run) {
        adminNotifier.sendToAdmins(run.failureSubject(),
                run.failureBody(configResolver.getDomain()),
                "backup failure for job " + run.jobName());
    }

    @Override
    public void notifyAdminsOfBackupRecovery(BackupRun run) {
        adminNotifier.sendToAdmins(run.recoverySubject(),
                run.recoveryBody(configResolver.getDomain()),
                "backup recovery for job " + run.jobName());
    }

    @Override
    public void notifyAdminsOfBackupServerDown(BackupServer server, ProbeResult cause) {
        adminNotifier.sendToAdmins(server.downSubject(),
                server.downBody(configResolver.getDomain(), cause),
                "backup server down: " + server.name());
    }

    @Override
    public void notifyAdminsOfBackupServerRecovered(BackupServer server) {
        adminNotifier.sendToAdmins(server.recoverySubject(),
                server.recoveryBody(configResolver.getDomain()),
                "backup server recovery: " + server.name());
    }

    /**
     * One rollup mail for the images that just became out of date. The rollup renders itself — subject, body
     * and the "Vaier does not pull" line are the domain's words, not this service's; it only sequences the send.
     */
    @Override
    public void notifyAdminsOfUpdateAvailable(ImageUpdateRollup rollup) {
        adminNotifier.sendToAdmins(rollup.subject(),
                rollup.body(configResolver.getDomain()),
                "update available for " + rollup.images().size() + " image(s)");
    }

}
