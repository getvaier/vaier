package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.NotifyAdminsOfPeerTransitionUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.PeerSnapshot;
import net.vaier.domain.User;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForGettingUsers;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import net.vaier.domain.port.ForSendingNotificationEmail;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class NotificationService implements NotifyAdminsOfPeerTransitionUseCase {

    private static final int DEFAULT_SMTP_PORT = 587;

    private final ForGettingUsers forGettingUsers;
    private final ForPersistingAppConfiguration configPersistence;
    private final ForReadingStoredSmtpPassword storedPasswordReader;
    private final ForSendingNotificationEmail emailSender;
    private final ConfigResolver configResolver;

    public NotificationService(ForGettingUsers forGettingUsers,
                               ForPersistingAppConfiguration configPersistence,
                               ForReadingStoredSmtpPassword storedPasswordReader,
                               ForSendingNotificationEmail emailSender,
                               ConfigResolver configResolver) {
        this.forGettingUsers = forGettingUsers;
        this.configPersistence = configPersistence;
        this.storedPasswordReader = storedPasswordReader;
        this.emailSender = emailSender;
        this.configResolver = configResolver;
    }

    @Override
    public void notifyAdmins(PeerSnapshot snapshot) {
        Optional<VaierConfig> maybeConfig = configPersistence.load();
        if (maybeConfig.isEmpty() || !maybeConfig.get().isSmtpConfigured()) {
            log.debug("SMTP not configured; skipping admin notification for {}", snapshot.name());
            return;
        }
        VaierConfig config = maybeConfig.get();

        Optional<String> password = storedPasswordReader.readStoredPassword().filter(p -> !p.isBlank());
        if (password.isEmpty()) {
            log.debug("SMTP password not stored; skipping admin notification for {}", snapshot.name());
            return;
        }

        List<String> recipients = forGettingUsers.getUsers().stream()
                .filter(User::isAdmin)
                .map(User::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .toList();

        if (recipients.isEmpty()) {
            log.debug("No admin users with email; skipping notification for {}", snapshot.name());
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
                    snapshot.notificationSubject(),
                    snapshot.notificationBody(configResolver.getDomain()));
            log.info("Sent admin notification: {} is now {}", snapshot.name(),
                    snapshot.connected() ? "connected" : "disconnected");
        } catch (Exception e) {
            log.warn("Failed to send admin notification for peer {}: {}", snapshot.name(), e.getMessage());
        }
    }
}
