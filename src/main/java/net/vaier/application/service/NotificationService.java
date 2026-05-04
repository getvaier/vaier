package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetUsersUseCase;
import net.vaier.application.NotifyAdminsOfPeerTransitionUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.PeerSnapshot;
import net.vaier.domain.User;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import net.vaier.domain.port.ForSendingNotificationEmail;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class NotificationService implements NotifyAdminsOfPeerTransitionUseCase {

    private static final String ADMINS_GROUP = "admins";
    private static final int DEFAULT_SMTP_PORT = 587;

    private final GetUsersUseCase getUsersUseCase;
    private final ForPersistingAppConfiguration configPersistence;
    private final ForReadingStoredSmtpPassword storedPasswordReader;
    private final ForSendingNotificationEmail emailSender;
    private final ConfigResolver configResolver;

    public NotificationService(GetUsersUseCase getUsersUseCase,
                               ForPersistingAppConfiguration configPersistence,
                               ForReadingStoredSmtpPassword storedPasswordReader,
                               ForSendingNotificationEmail emailSender,
                               ConfigResolver configResolver) {
        this.getUsersUseCase = getUsersUseCase;
        this.configPersistence = configPersistence;
        this.storedPasswordReader = storedPasswordReader;
        this.emailSender = emailSender;
        this.configResolver = configResolver;
    }

    @Override
    public void notifyAdmins(PeerSnapshot snapshot) {
        Optional<VaierConfig> maybeConfig = configPersistence.load();
        if (maybeConfig.isEmpty() || !smtpConfigured(maybeConfig.get())) {
            log.debug("SMTP not configured; skipping admin notification for {}", snapshot.name());
            return;
        }
        VaierConfig config = maybeConfig.get();

        Optional<String> password = storedPasswordReader.readStoredPassword().filter(p -> !p.isBlank());
        if (password.isEmpty()) {
            log.debug("SMTP password not stored; skipping admin notification for {}", snapshot.name());
            return;
        }

        List<String> recipients = getUsersUseCase.getUsers().stream()
                .filter(u -> u.getGroups() != null && u.getGroups().contains(ADMINS_GROUP))
                .map(User::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .toList();

        if (recipients.isEmpty()) {
            log.debug("No admin users with email; skipping notification for {}", snapshot.name());
            return;
        }

        String subject = "[Vaier] " + snapshot.name() + " is now "
                + (snapshot.connected() ? "connected" : "disconnected");
        String body = buildBody(snapshot);

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
            log.info("Sent admin notification: {} is now {}", snapshot.name(),
                    snapshot.connected() ? "connected" : "disconnected");
        } catch (Exception e) {
            log.warn("Failed to send admin notification for peer {}: {}", snapshot.name(), e.getMessage());
        }
    }

    private boolean smtpConfigured(VaierConfig config) {
        return config.getSmtpHost() != null && !config.getSmtpHost().isBlank()
                && config.getSmtpUsername() != null && !config.getSmtpUsername().isBlank();
    }

    private String buildBody(PeerSnapshot snapshot) {
        StringBuilder body = new StringBuilder();
        body.append("Machine: ").append(snapshot.name()).append("\n");
        body.append("Type: ").append(snapshot.peerType().name()).append("\n");
        body.append("Status: ").append(snapshot.connected() ? "connected" : "disconnected").append("\n");
        if (snapshot.latestHandshakeEpochSeconds() > 0) {
            body.append("Last handshake: ")
                    .append(Instant.ofEpochSecond(snapshot.latestHandshakeEpochSeconds()))
                    .append("\n");
        }
        if (snapshot.lanAddress() != null && !snapshot.lanAddress().isBlank()) {
            body.append("LAN address: ").append(snapshot.lanAddress()).append("\n");
        }
        String domain = configResolver.getDomain();
        if (domain != null && !domain.isBlank()) {
            body.append("\nVaier UI: https://vaier.").append(domain).append("/vpn-peers.html\n");
        }
        return body.toString();
    }
}
