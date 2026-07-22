package net.vaier.adapter.driven;

import net.vaier.config.ConfigResolver;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.Role;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAccessEntries;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import net.vaier.domain.port.ForSendingNotificationEmail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the SMTP send primitive (recipient resolution, SMTP gating, exception-swallow) and the
 * new-pending-identity alert — the logic that used to live on NotificationService.
 */
@ExtendWith(MockitoExtension.class)
class AdminNotificationEmailAdapterTest {

    @Mock ForPersistingAccessEntries accessStore;
    @Mock ForPersistingAppConfiguration configPersistence;
    @Mock ForReadingStoredSmtpPassword storedPasswordReader;
    @Mock ForSendingNotificationEmail emailSender;
    @Mock ConfigResolver configResolver;

    @InjectMocks AdminNotificationEmailAdapter adapter;

    private VaierConfig smtpConfigured() {
        return VaierConfig.builder()
                .domain("example.com")
                .smtpHost("smtp.example.com")
                .smtpPort(587)
                .smtpUsername("vaier@example.com")
                .smtpSender("noreply@example.com")
                .build();
    }

    private AccessEntry admin(String email) {
        return AccessEntry.builder().email(email).role(Role.ADMIN).groups(List.of()).build();
    }

    private AccessEntry user(String email) {
        return AccessEntry.builder().email(email).role(Role.USER).groups(List.of()).build();
    }

    private AccessEntry pending(String email) {
        return AccessEntry.builder().email(email).role(Role.PENDING).groups(List.of()).build();
    }

    // --- sendToAdmins primitive ---

    @Test
    void sendToAdmins_sendsToEveryAdmin_excludingUsersAndPendings() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("smtpPass"));
        when(accessStore.getEntries()).thenReturn(List.of(
                admin("alice@example.com"),
                admin("bob@example.com"),
                user("carol@example.com"),
                pending("dave@example.com")
        ));

        adapter.sendToAdmins("subject", "body", "ctx");

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                recipients.capture(), any(), any());
        assertThat(recipients.getValue()).containsExactlyInAnyOrder("alice@example.com", "bob@example.com");
    }

    @Test
    void sendToAdmins_skipsWhenSmtpHostNotConfigured() {
        when(configPersistence.load()).thenReturn(Optional.of(VaierConfig.builder().domain("example.com").build()));

        adapter.sendToAdmins("subject", "body", "ctx");

        verify(emailSender, never()).sendEmail(any(), anyInt(), any(), any(), any(), anyList(), any(), any());
    }

    @Test
    void sendToAdmins_skipsWhenSmtpPasswordNotStored() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.empty());

        adapter.sendToAdmins("subject", "body", "ctx");

        verify(emailSender, never()).sendEmail(any(), anyInt(), any(), any(), any(), anyList(), any(), any());
    }

    @Test
    void sendToAdmins_skipsCleanlyWhenNoAdminEmails() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(
                user("carol@example.com"),
                pending("dave@example.com")
        ));

        org.assertj.core.api.Assertions.assertThatCode(() -> adapter.sendToAdmins("s", "b", "ctx"))
                .doesNotThrowAnyException();

        verify(emailSender, never()).sendEmail(any(), anyInt(), any(), any(), any(), anyList(), any(), any());
    }

    @Test
    void sendToAdmins_skipsAdminsWithBlankEmail() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(
                admin("alice@example.com"),
                admin("")
        ));

        adapter.sendToAdmins("subject", "body", "ctx");

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                recipients.capture(), any(), any());
        assertThat(recipients.getValue()).containsExactly("alice@example.com");
    }

    @Test
    void sendToAdmins_swallowsSenderExceptions() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(admin("alice@example.com")));
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                        anyList(), any(), any());

        org.assertj.core.api.Assertions.assertThatCode(() -> adapter.sendToAdmins("s", "b", "ctx"))
                .doesNotThrowAnyException();
    }

    // --- new pending identity (access-request alert) ---

    @Test
    void notifyNewPendingIdentity_sendsAccessRequestEmailToEveryAdmin() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(
                admin("alice@example.com"),
                user("carol@example.com")));
        when(configResolver.getDomain()).thenReturn("example.com");

        adapter.notifyNewPendingIdentity("newcomer@example.com");

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                recipients.capture(), subject.capture(), body.capture());
        assertThat(recipients.getValue()).containsExactly("alice@example.com");
        assertThat(subject.getValue()).isEqualTo("[Vaier] New access request awaiting approval");
        assertThat(body.getValue()).contains("newcomer@example.com");
        assertThat(body.getValue()).contains("vaier.example.com/admin.html#users");
    }

    @Test
    void notifyNewPendingIdentity_skipsWhenSmtpNotConfigured() {
        when(configPersistence.load()).thenReturn(Optional.of(VaierConfig.builder().build()));

        adapter.notifyNewPendingIdentity("newcomer@example.com");

        verify(emailSender, never()).sendEmail(any(), anyInt(), any(), any(), any(), anyList(), any(), any());
    }

    @Test
    void notifyNewPendingIdentity_skipsWhenNoAdminEmails() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(user("carol@example.com")));

        adapter.notifyNewPendingIdentity("newcomer@example.com");

        verify(emailSender, never()).sendEmail(any(), anyInt(), any(), any(), any(), anyList(), any(), any());
    }
}
