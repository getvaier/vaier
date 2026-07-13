package net.vaier.application.service;

import net.vaier.config.ConfigResolver;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerSnapshot;
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

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock ForPersistingAccessEntries accessStore;
    @Mock ForPersistingAppConfiguration configPersistence;
    @Mock ForReadingStoredSmtpPassword storedPasswordReader;
    @Mock ForSendingNotificationEmail emailSender;
    @Mock ConfigResolver configResolver;

    @InjectMocks NotificationService service;

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

    private PeerSnapshot snapshot(boolean connected) {
        return new PeerSnapshot("file-server", MachineType.UBUNTU_SERVER, connected, 1700000000L, "192.168.1.50");
    }

    @Test
    void notifyAdmins_sendsEmailToEveryAccessEntryAdmin_excludingUsersAndPendings() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("smtpPass"));
        when(accessStore.getEntries()).thenReturn(List.of(
                admin("alice@example.com"),
                admin("bob@example.com"),
                user("carol@example.com"),
                pending("dave@example.com")
        ));

        service.notifyAdmins(snapshot(false));

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                recipients.capture(), any(), any());
        assertThat(recipients.getValue()).containsExactlyInAnyOrder("alice@example.com", "bob@example.com");
    }

    @Test
    void notifyAdmins_subjectIncludesPeerNameAndNewState_disconnected() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(admin("alice@example.com")));

        service.notifyAdmins(snapshot(false));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), subject.capture(), any());
        assertThat(subject.getValue()).isEqualTo("[Vaier] file-server is now disconnected");
    }

    @Test
    void notifyAdmins_subjectIncludesPeerNameAndNewState_connected() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(admin("alice@example.com")));

        service.notifyAdmins(snapshot(true));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), subject.capture(), any());
        assertThat(subject.getValue()).isEqualTo("[Vaier] file-server is now connected");
    }

    @Test
    void notifyAdmins_bodyIncludesPeerDetails_andLinkToVaier() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(admin("alice@example.com")));
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdmins(snapshot(false));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), any(), body.capture());
        String b = body.getValue();
        assertThat(b).contains("file-server");
        assertThat(b).contains("UBUNTU_SERVER");
        assertThat(b).contains("192.168.1.50");
        assertThat(b).contains("vaier.example.com");
    }

    @Test
    void notifyAdmins_skipsWhenSmtpHostNotConfigured() {
        when(configPersistence.load()).thenReturn(Optional.of(VaierConfig.builder()
                .domain("example.com")
                .build()));

        service.notifyAdmins(snapshot(false));

        verify(emailSender, never()).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), any(), any());
    }

    @Test
    void notifyAdmins_skipsWhenSmtpPasswordNotStored() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.empty());

        service.notifyAdmins(snapshot(false));

        verify(emailSender, never()).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), any(), any());
    }

    @Test
    void notifyAdmins_skipsCleanlyWhenNoAdminEmails() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(
                user("carol@example.com"),
                pending("dave@example.com")
        ));

        org.assertj.core.api.Assertions.assertThatCode(() -> service.notifyAdmins(snapshot(false)))
                .doesNotThrowAnyException();

        verify(emailSender, never()).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), any(), any());
    }

    @Test
    void notifyAdmins_skipsAdminsWithBlankEmail() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(
                admin("alice@example.com"),
                admin("")
        ));

        service.notifyAdmins(snapshot(false));

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                recipients.capture(), any(), any());
        assertThat(recipients.getValue()).containsExactly("alice@example.com");
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

        service.notifyNewPendingIdentity("newcomer@example.com");

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

        service.notifyNewPendingIdentity("newcomer@example.com");

        verify(emailSender, never()).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), any(), any());
    }

    @Test
    void notifyNewPendingIdentity_skipsWhenNoAdminEmails() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(
                user("carol@example.com")));

        service.notifyNewPendingIdentity("newcomer@example.com");

        verify(emailSender, never()).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), any(), any());
    }

    // --- disk-fill forecast (early-warning alert) ---

    @Test
    void notifyAdminsOfDiskFillForecast_sendsEarlyWarningToEveryAdmin() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(
                admin("alice@example.com"),
                user("carol@example.com")));
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfDiskFillForecast(
                new net.vaier.domain.DiskFillForecast("nas", 80, 1.0, java.time.Duration.ofHours(18)));

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                recipients.capture(), subject.capture(), body.capture());
        assertThat(recipients.getValue()).containsExactly("alice@example.com");
        assertThat(subject.getValue()).contains("nas").contains("18h");
        assertThat(body.getValue()).contains("nas").contains("80%").contains("vaier.example.com");
    }

    @Test
    void notifyAdminsOfDiskFillForecastCleared_sendsAllClearToEveryAdmin() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(admin("alice@example.com")));
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfDiskFillForecastCleared(
                new net.vaier.domain.DiskFillForecastCleared("nas", 60));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), subject.capture(), body.capture());
        assertThat(subject.getValue()).contains("nas");
        assertThat(body.getValue()).contains("60%");
    }

    // --- fleet-backup failure / recovery alerts ---

    private net.vaier.domain.BackupRun failedRun() {
        net.vaier.domain.BackupJob job = new net.vaier.domain.BackupJob("colina-home", "Colina 27",
            "nas-borg", List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false);
        return net.vaier.domain.BackupRun.fromExitCode(job, "run-1",
            java.time.Instant.parse("2026-07-08T02:00:00Z"),
            java.time.Instant.parse("2026-07-08T02:05:00Z"), 2, "borg failed");
    }

    private net.vaier.domain.BackupRun succeededRun() {
        net.vaier.domain.BackupJob job = new net.vaier.domain.BackupJob("colina-home", "Colina 27",
            "nas-borg", List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false);
        return net.vaier.domain.BackupRun.fromExitCode(job, "run-2",
            java.time.Instant.parse("2026-07-08T02:00:00Z"),
            java.time.Instant.parse("2026-07-08T02:40:00Z"), 0, "12 files, 3 GB");
    }

    @Test
    void notifyAdminsOfBackupFailureSendsToAdmins() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(
                admin("alice@example.com"),
                user("carol@example.com")));
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfBackupFailure(failedRun());

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                recipients.capture(), subject.capture(), body.capture());
        assertThat(recipients.getValue()).containsExactly("alice@example.com");
        assertThat(subject.getValue()).isEqualTo("[Vaier] Backup failed: colina-home on Colina 27");
        assertThat(body.getValue()).contains("colina-home").contains("vaier.example.com");
    }

    @Test
    void notifyAdminsOfBackupRecoverySendsAllClearToAdmins() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(admin("alice@example.com")));
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfBackupRecovery(succeededRun());

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), subject.capture(), any());
        assertThat(subject.getValue()).isEqualTo("[Vaier] Backup recovered: colina-home on Colina 27");
    }

    // --- fleet-backup server down / recovery alerts ---

    private net.vaier.domain.BackupServer backupServer() {
        return new net.vaier.domain.BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    @Test
    void notifyAdminsOfBackupServerDownSendsToAdmins() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(
                admin("alice@example.com"),
                user("carol@example.com")));
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfBackupServerDown(backupServer(),
                net.vaier.domain.port.ForProbingTcp.ProbeResult.REFUSED);

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                recipients.capture(), subject.capture(), body.capture());
        assertThat(recipients.getValue()).containsExactly("alice@example.com");
        assertThat(subject.getValue()).isEqualTo(backupServer().downSubject());
        assertThat(body.getValue()).contains("borg server container is down").contains("vaier.example.com");
    }

    @Test
    void notifyAdminsOfBackupServerRecoveredSendsAllClearToAdmins() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(admin("alice@example.com")));
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfBackupServerRecovered(backupServer());

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), subject.capture(), any());
        assertThat(subject.getValue()).isEqualTo(backupServer().recoverySubject());
    }

    @Test
    void notifyAdmins_swallowsSenderExceptionsSoSchedulerKeepsRunning() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(accessStore.getEntries()).thenReturn(List.of(admin("alice@example.com")));
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                        anyList(), any(), any());

        org.assertj.core.api.Assertions.assertThatCode(() -> service.notifyAdmins(snapshot(false)))
                .doesNotThrowAnyException();
    }
}
