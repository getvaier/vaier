package net.vaier.application.service;

import net.vaier.config.ConfigResolver;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerSnapshot;
import net.vaier.domain.port.ForSendingAdminNotification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationService now composes each alert's subject/body from the domain and hands them to the
 * {@link ForSendingAdminNotification} primitive; the SMTP machinery (recipient resolution, gating,
 * exception-swallow) and the new-pending-identity alert live in AdminNotificationEmailAdapter and
 * are covered by AdminNotificationEmailAdapterTest. These tests verify the domain composition and
 * the delegation.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock ForSendingAdminNotification adminNotifier;
    @Mock ConfigResolver configResolver;

    @InjectMocks NotificationService service;

    private PeerSnapshot snapshot(boolean connected) {
        return new PeerSnapshot("file-server", MachineType.UBUNTU_SERVER, connected, 1700000000L, "192.168.1.50");
    }

    @Test
    void notifyAdmins_subjectIncludesPeerNameAndNewState_disconnected() {
        service.notifyAdmins(snapshot(false));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), any(), any());
        assertThat(subject.getValue()).isEqualTo("[Vaier] file-server is now disconnected");
    }

    @Test
    void notifyAdmins_subjectIncludesPeerNameAndNewState_connected() {
        service.notifyAdmins(snapshot(true));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), any(), any());
        assertThat(subject.getValue()).isEqualTo("[Vaier] file-server is now connected");
    }

    @Test
    void notifyAdmins_bodyIncludesPeerDetails_andLinkToVaier() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdmins(snapshot(false));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(any(), body.capture(), any());
        String b = body.getValue();
        assertThat(b).contains("file-server");
        assertThat(b).contains("UBUNTU_SERVER");
        assertThat(b).contains("192.168.1.50");
        assertThat(b).contains("vaier.example.com");
    }

    // --- disk-fill forecast (early-warning alert) ---

    @Test
    void notifyAdminsOfDiskFillForecast_sendsEarlyWarning() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfDiskFillForecast(
                new net.vaier.domain.DiskFillForecast("nas", "/volume1", 80, 1.0, java.time.Duration.ofHours(18)));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).contains("nas").contains("18h");
        assertThat(body.getValue()).contains("nas").contains("80%").contains("vaier.example.com");
    }

    @Test
    void notifyAdminsOfDiskFillForecastCleared_sendsAllClear() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfDiskFillForecastCleared(
                new net.vaier.domain.DiskFillForecastCleared("nas", "/volume1", 60));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
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
    void notifyAdminsOfBackupFailure_composesSubjectAndBody() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfBackupFailure(failedRun());

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).isEqualTo("[Vaier] Backup failed: colina-home on Colina 27");
        assertThat(body.getValue()).contains("colina-home").contains("vaier.example.com");
    }

    @Test
    void notifyAdminsOfBackupRecovery_composesAllClearSubject() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfBackupRecovery(succeededRun());

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), any(), any());
        assertThat(subject.getValue()).isEqualTo("[Vaier] Backup recovered: colina-home on Colina 27");
    }

    // --- fleet-backup server down / recovery alerts ---

    private net.vaier.domain.BackupServer backupServer() {
        return new net.vaier.domain.BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    @Test
    void notifyAdminsOfBackupServerDown_composesSubjectAndBody() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfBackupServerDown(backupServer(),
                net.vaier.domain.port.ForProbingTcp.ProbeResult.REFUSED);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).isEqualTo(backupServer().downSubject());
        assertThat(body.getValue()).contains("borg server container is down").contains("vaier.example.com");
    }

    @Test
    void notifyAdminsOfBackupServerRecovered_composesAllClearSubject() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfBackupServerRecovered(backupServer());

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), any(), any());
        assertThat(subject.getValue()).isEqualTo(backupServer().recoverySubject());
    }

    @Test
    void notifyAdminsOfUpdateAvailable_composesOneRollup() {
        when(configResolver.getDomain()).thenReturn("example.com");
        net.vaier.domain.ImageUpdateRollup rollup = new net.vaier.domain.ImageUpdateRollup(List.of(
                new net.vaier.domain.ScopedImage("Apalveien 5", "vaultwarden/server:latest"),
                new net.vaier.domain.ScopedImage("Colina 27", "lscr.io/linuxserver/wireguard:1.0.x")));

        service.notifyAdminsOfUpdateAvailable(rollup);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).isEqualTo(rollup.subject());
        assertThat(body.getValue())
                .contains("vaultwarden/server:latest on Apalveien 5")
                .contains("lscr.io/linuxserver/wireguard:1.0.x on Colina 27")
                .contains("vaier.example.com");
    }
}
