package net.vaier.application.service;

import java.time.Duration;
import java.time.Instant;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.Bundle;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupServer;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.BreachAttemptRollup;
import net.vaier.domain.DiskFillForecast;
import net.vaier.domain.DiskFillForecastCleared;
import net.vaier.domain.EnrolmentRequest;
import org.springframework.scheduling.annotation.Async;
import net.vaier.domain.LockoutWarning;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineNetworks;
import net.vaier.domain.MachineType;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Operator;
import net.vaier.domain.PeerSnapshot;
import net.vaier.domain.ReverseProxyAudit;
import net.vaier.domain.ReverseProxyConfig;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.port.ForProbingTcp.ProbeResult;
import net.vaier.domain.port.ForHoldingBundles;
import net.vaier.domain.port.ForSendingAdminNotification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock ForHoldingBundles forHoldingBundles;
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
                DiskFillForecast.builder()
                        .machineName("nas").mountPoint("/volume1")
                        .currentPercent(74).thresholdPercent(80)
                        .fillRateKbPerHour(43_690.0).runway(Duration.ofDays(5)).build());

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).contains("nas").contains("80% threshold").contains("5.0 days");
        assertThat(body.getValue()).contains("nas").contains("74%").contains("vaier.example.com");
    }

    // --- the reverse proxy audit (#354) ---

    @Test
    void notifyAdminsOfReverseProxyFindings_namesEachFindingAndSaysVaierChangedNothing() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfReverseProxyFindings(ReverseProxyAudit.of(ReverseProxyConfig.builder()
                .middlewares(List.of(ReverseProxyConfig.ConfiguredMiddleware.builder()
                        .protocol(ReverseProxyConfig.Protocol.HTTP).name("orphaned-redirect").build()))
                .build()));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).startsWith("[Vaier] ").contains("Reverse proxy config");
        assertThat(body.getValue()).contains("orphaned-redirect").contains("changed nothing")
                .contains("vaier.example.com");
    }

    @Test
    void notifyAdminsOfReverseProxyAuditRecovery_saysItIsClearAgain() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfReverseProxyAuditRecovery(
                ReverseProxyAudit.of(ReverseProxyConfig.empty()));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), any(), any());
        assertThat(subject.getValue()).startsWith("[Vaier] ").contains("clear again");
    }

    @Test
    void notifyAdminsOfDiskFillForecastCleared_sendsAllClear() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfDiskFillForecastCleared(
                new DiskFillForecastCleared("nas", "/volume1", 60));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).contains("nas");
        assertThat(body.getValue()).contains("60%");
    }

    // --- a machine with no way out (#357) ---

    /** What the observed fault looks like: an address, an on-link route, and no way out. */
    private static final MachineNetworks NO_WAY_OUT = MachineNetworks.parse(
            "2: eno1    inet 192.168.3.20/24 brd 192.168.3.255 scope global eno1");

    @Test
    void notifyAdminsOfMissingDefaultRoute_carriesTheDomainsWordsAndTheEvidence() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfMissingDefaultRoute("Apalveien 5", NO_WAY_OUT);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).isEqualTo(NO_WAY_OUT.missingDefaultRouteSubject("Apalveien 5"));
        assertThat(body.getValue()).contains("eno1 192.168.3.20/24").contains("vaier.example.com");
    }

    @Test
    void notifyAdminsOfDefaultRouteRestored_isTheAllClear() {
        MachineNetworks restored = MachineNetworks.parse("""
                2: eno1    inet 192.168.3.20/24 brd 192.168.3.255 scope global eno1
                default via 192.168.3.1 dev eno1 proto dhcp metric 100
                """);

        service.notifyAdminsOfDefaultRouteRestored("Apalveien 5", restored);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).isEqualTo(restored.defaultRouteRestoredSubject("Apalveien 5"));
        // The all-clear carries the fresh reading, so it never repeats the warning it is cancelling.
        assertThat(body.getValue()).contains("via eno1").doesNotContain("cannot fix it");
    }

    // --- fleet-backup failure / recovery alerts ---

    /** What the machine is called. A run holds its identity; the orchestrator supplies the name to render. */
    private static final String MACHINE = "Colina 27";

    private BackupRun failedRun() {
        BackupJob job = new BackupJob("colina-home", TestMachineIds.of(MACHINE),
            "nas-borg", List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false);
        return BackupRun.fromExitCode(job, "run-1",
            Instant.parse("2026-07-08T02:00:00Z"),
            Instant.parse("2026-07-08T02:05:00Z"), 2, "borg failed");
    }

    private BackupRun succeededRun() {
        BackupJob job = new BackupJob("colina-home", TestMachineIds.of(MACHINE),
            "nas-borg", List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false);
        return BackupRun.fromExitCode(job, "run-2",
            Instant.parse("2026-07-08T02:00:00Z"),
            Instant.parse("2026-07-08T02:40:00Z"), 0, "12 files, 3 GB");
    }

    @Test
    void notifyAdminsOfBackupFailure_composesSubjectAndBody() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfBackupFailure(failedRun(), MACHINE);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).isEqualTo("[Vaier] Backup failed: colina-home on Colina 27");
        assertThat(body.getValue()).contains("colina-home").contains("vaier.example.com");
    }

    @Test
    void notifyAdminsOfBackupRecovery_composesAllClearSubject() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfBackupRecovery(succeededRun(), MACHINE);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), any(), any());
        assertThat(subject.getValue()).isEqualTo("[Vaier] Backup recovered: colina-home on Colina 27");
    }

    // --- fleet-backup server down / recovery alerts ---

    private static final String SERVER_MACHINE = "NAS";

    private BackupServer backupServer() {
        return new BackupServer("nas-borg", TestMachineIds.of(SERVER_MACHINE), "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    @Test
    void notifyAdminsOfBackupServerDown_composesSubjectAndBody() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfBackupServerDown(backupServer(), SERVER_MACHINE, ProbeResult.REFUSED);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).isEqualTo(backupServer().downSubject());
        assertThat(body.getValue()).contains("borg server container is down").contains("vaier.example.com");
    }

    @Test
    void notifyAdminsOfBackupServerRecovered_composesAllClearSubject() {
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdminsOfBackupServerRecovered(backupServer(), SERVER_MACHINE);

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

    @Test
    void notifyAdminsOfEnrolmentRequest_saysWhoAndWhichCode_andLinksTheApproval() {
        when(configResolver.getDomain()).thenReturn("example.com");
        EnrolmentRequest request = EnrolmentRequest.open("Ruten",
            "Cdd32h4brltAwRS22xopgiyeyXUNv202FMgAoj1Hgio=", "4821", "ticket", System.currentTimeMillis());

        service.notifyAdminsOfEnrolmentRequest(request);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).contains("Ruten").contains("4821");
        assertThat(body.getValue()).contains("https://vaier.example.com/explorer.html?approve=4821");
    }

    @Test
    void notifyAdminsOfEnrolmentRequest_runsOffTheCallersThread() throws NoSuchMethodException {
        // The caller is a phone's anonymous POST; the SMTP round trip must not be its response time.
        assertThat(NotificationService.class
            .getMethod("notifyAdminsOfEnrolmentRequest", EnrolmentRequest.class)
            .isAnnotationPresent(Async.class)).isTrue();
    }

    @Test
    void notifyAdminsOfLockoutWarning_composesTheOperatorsOwnBlockedAddresses() {
        when(configResolver.getDomain()).thenReturn("example.com");
        LockoutWarning warning = new LockoutWarning(List.of(BlockDecision.builder()
                .id(7L).scenario("crowdsecurity/http-probing").sourceIp("10.13.13.6").type("ban")
                .duration("3h59m48s").build()));

        service.notifyAdminsOfLockoutWarning(warning);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).isEqualTo(warning.subject());
        assertThat(body.getValue()).contains("10.13.13.6").contains("vaier.example.com");
    }

    @Test
    void notifyAdminsOfBreachAttempt_composesOneRollup() {
        when(configResolver.getDomain()).thenReturn("example.com");
        BreachAttemptRollup rollup = new BreachAttemptRollup(List.of(BlockDecision.builder()
                .id(1L).scenario("crowdsecurity/ssh-bf").sourceIp("1.2.3.4").type("ban")
                .duration("3h59m48s").build()));

        service.notifyAdminsOfBreachAttempt(rollup);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendToAdmins(subject.capture(), body.capture(), any());
        assertThat(subject.getValue()).isEqualTo(rollup.subject());
        assertThat(body.getValue()).contains("1.2.3.4").contains("vaier.example.com");
    }

    // --- a bundle's link, by mail (#360) --------------------------------------------------------------

    private static final MachineId NAS = MachineId.of("41a14c07-b2b9-4e6f-bb48-3991a11bb862");

    /** The link is mailed to the operator who asked, and the bundle is kept a day so the link works. */
    @Test
    void emailBundle_mailsTheLinkToTheOperator_andKeepsTheBundleADay() {
        Bundle bundle = Bundle.offer(NAS, "NAS", List.of("/volume1/photo/a.jpg"), "pictures", System.currentTimeMillis());
        when(forHoldingBundles.find(bundle.id())).thenReturn(Optional.of(bundle));
        when(configResolver.getDomain()).thenReturn("example.com");
        when(adminNotifier.sendTo(eq("geir@example.com"), any(), any(), any())).thenReturn(true);

        String to = service.email(bundle.id(), Operator.of("Geir@Example.com"));

        assertThat(to).isEqualTo("geir@example.com");
        ArgumentCaptor<Bundle> kept = ArgumentCaptor.forClass(Bundle.class);
        verify(forHoldingBundles).hold(kept.capture());
        assertThat(kept.getValue().id()).isEqualTo(bundle.id());
        assertThat(kept.getValue().expired(System.currentTimeMillis() + Bundle.TTL.toMillis() + 1)).isFalse();
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(adminNotifier).sendTo(eq("geir@example.com"), eq("Your files from NAS: pictures.zip"), body.capture(), any());
        assertThat(body.getValue()).contains("https://vaier.example.com/chat/bundles/" + bundle.id());
    }

    @Test
    void emailBundle_refusesWhenTheBundleIsGone_orNobodyIsSignedIn_orMailIsNotSetUp() {
        when(forHoldingBundles.find("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.email("gone", Operator.of("geir@example.com")))
            .isInstanceOf(NotFoundException.class).hasMessage("That download is gone; ask again.");

        assertThatThrownBy(() -> service.email("any", Operator.of(null)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Vaier does not know your email address.");

        Bundle bundle = Bundle.offer(NAS, "NAS", List.of("/a"), "x", System.currentTimeMillis());
        when(forHoldingBundles.find(bundle.id())).thenReturn(Optional.of(bundle));
        when(configResolver.getDomain()).thenReturn("example.com");
        when(adminNotifier.sendTo(any(), any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.email(bundle.id(), Operator.of("geir@example.com")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Vaier could not send mail; check the SMTP settings.");
    }
}
