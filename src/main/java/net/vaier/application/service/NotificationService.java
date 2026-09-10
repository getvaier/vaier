package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.NotifyAdminsOfBackupFailureUseCase;
import net.vaier.application.NotifyAdminsOfBackupServerDownUseCase;
import net.vaier.application.NotifyAdminsOfBreachAttemptUseCase;
import net.vaier.application.NotifyAdminsOfDiskFillForecastUseCase;
import net.vaier.application.NotifyAdminsOfEnrolmentRequestUseCase;
import net.vaier.application.NotifyAdminsOfLockoutWarningUseCase;
import net.vaier.application.EmailBundleUseCase;
import net.vaier.application.NotifyAdminsOfPeerTransitionUseCase;
import net.vaier.application.NotifyAdminsOfRemoteDiskPressureUseCase;
import net.vaier.application.NotifyAdminsOfUpdateAvailableUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupServer;
import net.vaier.domain.BreachAttemptRollup;
import net.vaier.domain.DiskFillForecast;
import net.vaier.domain.DiskFillForecastCleared;
import net.vaier.domain.ImageUpdateRollup;
import net.vaier.domain.Bundle;
import net.vaier.domain.BundleMailNotice;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Operator;
import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.JoinRequestNotice;
import net.vaier.domain.LockoutWarning;
import net.vaier.domain.RemoteDiskUsage;
import net.vaier.domain.PeerSnapshot;
import net.vaier.domain.port.ForProbingTcp.ProbeResult;
import net.vaier.domain.port.ForHoldingBundles;
import net.vaier.domain.port.ForSendingAdminNotification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class NotificationService implements
        EmailBundleUseCase,
        NotifyAdminsOfPeerTransitionUseCase,
        NotifyAdminsOfRemoteDiskPressureUseCase,
        NotifyAdminsOfDiskFillForecastUseCase,
        NotifyAdminsOfBackupFailureUseCase,
        NotifyAdminsOfBackupServerDownUseCase,
        NotifyAdminsOfUpdateAvailableUseCase,
        NotifyAdminsOfBreachAttemptUseCase,
        NotifyAdminsOfLockoutWarningUseCase,
        NotifyAdminsOfEnrolmentRequestUseCase {

    private final ForSendingAdminNotification adminNotifier;
    private final ConfigResolver configResolver;
    private final ForHoldingBundles forHoldingBundles;

    public NotificationService(ForSendingAdminNotification adminNotifier,
                               ConfigResolver configResolver,
                               ForHoldingBundles forHoldingBundles) {
        this.adminNotifier = adminNotifier;
        this.configResolver = configResolver;
        this.forHoldingBundles = forHoldingBundles;
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
    public void notifyAdminsOfBackupFailure(BackupRun run, String machineLabel) {
        adminNotifier.sendToAdmins(run.failureSubject(machineLabel),
                run.failureBody(machineLabel, configResolver.getDomain()),
                "backup failure for job " + run.jobName());
    }

    @Override
    public void notifyAdminsOfBackupRecovery(BackupRun run, String machineLabel) {
        adminNotifier.sendToAdmins(run.recoverySubject(machineLabel),
                run.recoveryBody(machineLabel, configResolver.getDomain()),
                "backup recovery for job " + run.jobName());
    }

    @Override
    public void notifyAdminsOfBackupServerDown(BackupServer server, String machineLabel, ProbeResult cause) {
        adminNotifier.sendToAdmins(server.downSubject(),
                server.downBody(machineLabel, configResolver.getDomain(), cause),
                "backup server down: " + server.name());
    }

    @Override
    public void notifyAdminsOfBackupServerRecovered(BackupServer server, String machineLabel) {
        adminNotifier.sendToAdmins(server.recoverySubject(),
                server.recoveryBody(machineLabel, configResolver.getDomain()),
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

    /**
     * One rollup mail for the block decisions that just appeared. The rollup renders itself; this
     * service only sequences the send.
     */
    @Override
    public void notifyAdminsOfBreachAttempt(BreachAttemptRollup rollup) {
        adminNotifier.sendToAdmins(rollup.subject(),
                rollup.body(configResolver.getDomain()),
                "breach attempt: " + rollup.decisions().size() + " new block decision(s)");
    }

    /**
     * One warning mail for the operator's own addresses that CrowdSec has started blocking. Its own
     * subject and body come from the domain — this is not a breach attempt and must not read like one.
     */
    // Off the phone's own request: an anonymous POST must not wait on an SMTP round trip.
    @Async
    @Override
    public void notifyAdminsOfEnrolmentRequest(EnrolmentRequest request) {
        JoinRequestNotice notice = JoinRequestNotice.from(request, System.currentTimeMillis());
        adminNotifier.sendToAdmins(notice.subject(), notice.body(configResolver.getDomain()),
                "join request: code " + request.code());
    }

    @Override
    public void notifyAdminsOfLockoutWarning(LockoutWarning warning) {
        adminNotifier.sendToAdmins(warning.subject(),
                warning.body(configResolver.getDomain()),
                "lockout warning: " + warning.decisions().size() + " trusted address(es) blocked");
    }


    /**
     * A bundle's link, by mail, to the operator who asked (#360): the bundle is kept a day so the link works
     * when they get to it. Whether the mail went is the adapter's word, and not going is said, never swallowed.
     */
    @Override
    public String email(String bundleId, Operator operator) {
        String to = operator.email()
            .orElseThrow(() -> new IllegalArgumentException("Vaier does not know your email address."));
        long now = System.currentTimeMillis();
        Bundle bundle = forHoldingBundles.find(bundleId)
            .orElseThrow(() -> new NotFoundException("That download is gone; ask again."))
            .requireLive(now)
            .keptForADay(now);
        forHoldingBundles.hold(bundle);
        BundleMailNotice notice = BundleMailNotice.of(bundle, configResolver.getDomain());
        if (!adminNotifier.sendTo(to, notice.subject(), notice.body(), "bundle " + bundle.id())) {
            throw new IllegalArgumentException("Vaier could not send mail; check the SMTP settings.");
        }
        return to;
    }
}
