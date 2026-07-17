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
import net.vaier.domain.AccessEntry;
import net.vaier.domain.PendingIdentity;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForNotifyingAdmins;
import net.vaier.domain.port.ForProbingTcp.ProbeResult;
import net.vaier.domain.port.ForPersistingAccessEntries;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import net.vaier.domain.port.ForSendingNotificationEmail;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class NotificationService implements
        NotifyAdminsOfPeerTransitionUseCase,
        NotifyAdminsOfRemoteDiskPressureUseCase,
        NotifyAdminsOfDiskFillForecastUseCase,
        NotifyAdminsOfBackupFailureUseCase,
        NotifyAdminsOfBackupServerDownUseCase,
        NotifyAdminsOfUpdateAvailableUseCase,
        ForNotifyingAdmins {

    private static final int DEFAULT_SMTP_PORT = 587;

    private final ForPersistingAccessEntries accessStore;
    private final ForPersistingAppConfiguration configPersistence;
    private final ForReadingStoredSmtpPassword storedPasswordReader;
    private final ForSendingNotificationEmail emailSender;
    private final ConfigResolver configResolver;

    public NotificationService(ForPersistingAccessEntries accessStore,
                               ForPersistingAppConfiguration configPersistence,
                               ForReadingStoredSmtpPassword storedPasswordReader,
                               ForSendingNotificationEmail emailSender,
                               ConfigResolver configResolver) {
        this.accessStore = accessStore;
        this.configPersistence = configPersistence;
        this.storedPasswordReader = storedPasswordReader;
        this.emailSender = emailSender;
        this.configResolver = configResolver;
    }

    @Override
    public void notifyAdmins(PeerSnapshot snapshot) {
        sendToAdmins(snapshot.notificationSubject(),
                snapshot.notificationBody(configResolver.getDomain()),
                "peer " + snapshot.name());
    }

    @Override
    public void notifyAdminsOfRemoteDiskPressure(RemoteDiskUsage usage, int thresholdPercent) {
        sendToAdmins(usage.pressureSubject(),
                usage.pressureBody(thresholdPercent, configResolver.getDomain()),
                "remote disk pressure on " + usage.machineName());
    }

    @Override
    public void notifyAdminsOfRemoteDiskRecovery(RemoteDiskUsage usage, int thresholdPercent) {
        sendToAdmins(usage.recoverySubject(),
                usage.pressureBody(thresholdPercent, configResolver.getDomain()),
                "remote disk recovery on " + usage.machineName());
    }

    @Override
    public void notifyAdminsOfDiskFillForecast(DiskFillForecast forecast) {
        sendToAdmins(forecast.forecastSubject(),
                forecast.forecastBody(configResolver.getDomain()),
                "disk-fill forecast on " + forecast.machineName());
    }

    @Override
    public void notifyAdminsOfDiskFillForecastCleared(DiskFillForecastCleared cleared) {
        sendToAdmins(cleared.clearedSubject(),
                cleared.clearedBody(configResolver.getDomain()),
                "disk-fill forecast cleared on " + cleared.machineName());
    }

    @Override
    public void notifyAdminsOfBackupFailure(BackupRun run) {
        sendToAdmins(run.failureSubject(),
                run.failureBody(configResolver.getDomain()),
                "backup failure for job " + run.jobName());
    }

    @Override
    public void notifyAdminsOfBackupRecovery(BackupRun run) {
        sendToAdmins(run.recoverySubject(),
                run.recoveryBody(configResolver.getDomain()),
                "backup recovery for job " + run.jobName());
    }

    @Override
    public void notifyAdminsOfBackupServerDown(BackupServer server, ProbeResult cause) {
        sendToAdmins(server.downSubject(),
                server.downBody(configResolver.getDomain(), cause),
                "backup server down: " + server.name());
    }

    @Override
    public void notifyAdminsOfBackupServerRecovered(BackupServer server) {
        sendToAdmins(server.recoverySubject(),
                server.recoveryBody(configResolver.getDomain()),
                "backup server recovery: " + server.name());
    }

    /**
     * One rollup mail for the images that just became out of date. The rollup renders itself — subject, body
     * and the "Vaier does not pull" line are the domain's words, not this service's; it only sequences the send.
     */
    @Override
    public void notifyAdminsOfUpdateAvailable(ImageUpdateRollup rollup) {
        sendToAdmins(rollup.subject(),
                rollup.body(configResolver.getDomain()),
                "update available for " + rollup.images().size() + " image(s)");
    }

    /**
     * Notify admins of a brand-new pending access entry. Runs asynchronously so it never adds
     * latency to the forward-auth path that triggers it, and is wrapped so any failure is logged
     * rather than propagated. Recipients are the AccessEntry ADMINs, consistent with the other alerts.
     */
    @Async
    @Override
    public void notifyNewPendingIdentity(String email) {
        try {
            PendingIdentity identity = new PendingIdentity(email);
            sendToAdmins(identity.notificationSubject(),
                    identity.notificationBody(configResolver.getDomain()),
                    "new pending identity " + email);
        } catch (Exception e) {
            log.warn("Failed to notify admins of new pending identity {}: {}", email, e.getMessage());
        }
    }

    /** Send {@code subject}/{@code body} to every admin with an email, if SMTP is fully configured. */
    private void sendToAdmins(String subject, String body, String context) {
        Optional<VaierConfig> maybeConfig = configPersistence.load();
        if (maybeConfig.isEmpty() || !maybeConfig.get().isSmtpConfigured()) {
            log.debug("SMTP not configured; skipping admin notification for {}", context);
            return;
        }
        VaierConfig config = maybeConfig.get();

        Optional<String> password = storedPasswordReader.readStoredPassword().filter(p -> !p.isBlank());
        if (password.isEmpty()) {
            log.debug("SMTP password not stored; skipping admin notification for {}", context);
            return;
        }

        List<String> recipients = accessStore.getEntries().stream()
                .filter(AccessEntry::isAdmin)
                .map(AccessEntry::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .toList();

        if (recipients.isEmpty()) {
            log.debug("No admin users with email; skipping notification for {}", context);
            return;
        }

        try {
            emailSender.sendEmail(
                    config.getSmtpHost(),
                    config.getSmtpPort() != null ? config.getSmtpPort() : DEFAULT_SMTP_PORT,
                    config.getSmtpUsername(),
                    password.get(),
                    config.getSmtpSender(),
                    recipients,
                    subject,
                    body);
            log.info("Sent admin notification: {}", subject);
        } catch (Exception e) {
            log.warn("Failed to send admin notification for {}: {}", context, e.getMessage());
        }
    }
}
