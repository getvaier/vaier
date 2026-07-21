package net.vaier.rest;

import net.vaier.application.AdoptDiscoveredMachineUseCase;
import net.vaier.application.GetDiscoveredLanMachinesUseCase;
import net.vaier.application.GetDiscoveredLanMachinesUseCase.LanScanSnapshot;
import net.vaier.application.GetDiscoveredLanMachinesUseCase.ScanStatus;
import net.vaier.application.IgnoreLanMachineUseCase;
import net.vaier.application.ListScannableLansUseCase;
import net.vaier.application.ListScannableLansUseCase.ScannableLan;
import net.vaier.application.ScanLanAnchorUseCase;
import net.vaier.application.ScanLanUseCase;
import net.vaier.application.UnignoreLanMachineUseCase;
import net.vaier.application.VerifySshCredentialUseCase;
import net.vaier.application.AdoptDiscoveredMachineUseCase.AdoptionOutcome;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.DiscoveredLanMachine;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.LanServer;
import net.vaier.domain.SshCredentialDraft;
import net.vaier.domain.SshCredentialVerification;
import net.vaier.rest.LanScannerRestController.AdoptRequest;
import net.vaier.rest.LanScannerRestController.AdoptResponse;
import net.vaier.rest.LanScannerRestController.DiscoveredMachineDto;
import net.vaier.rest.LanScannerRestController.IgnoreRequest;
import net.vaier.rest.LanScannerRestController.LanScanResponse;
import net.vaier.rest.LanScannerRestController.SshCredentialBlock;
import net.vaier.rest.LanScannerRestController.SshCredentialTestRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanScannerRestControllerTest {

    @Mock ScanLanUseCase scanLanUseCase;
    @Mock ScanLanAnchorUseCase scanLanAnchorUseCase;
    @Mock ListScannableLansUseCase listScannableLansUseCase;
    @Mock GetDiscoveredLanMachinesUseCase getDiscoveredLanMachinesUseCase;
    @Mock IgnoreLanMachineUseCase ignoreLanMachineUseCase;
    @Mock UnignoreLanMachineUseCase unignoreLanMachineUseCase;
    @Mock AdoptDiscoveredMachineUseCase adoptDiscoveredMachineUseCase;
    @Mock VerifySshCredentialUseCase verifySshCredentialUseCase;

    @InjectMocks LanScannerRestController controller;

    @Test
    void postWithNoAnchorTriggersAFullScanAndReturns202() {
        ResponseEntity<Void> response = controller.startScan(null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(scanLanUseCase).startScan();
    }

    @Test
    void postWithAnAnchorTriggersATargetedScanAndReturns202() {
        ResponseEntity<Void> response = controller.startScan("colina27");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(scanLanAnchorUseCase).startScan("colina27");
    }

    @Test
    void getLansReturnsTheScannableLans() {
        when(listScannableLansUseCase.scannableLans()).thenReturn(List.of(
            new ScannableLan("apalveien5", "Apalveien 5", "192.168.3.0/24"),
            new ScannableLan("Vaier server", "Vaier server", "172.31.0.0/16")));

        var body = controller.getLans().getBody();

        assertThat(body).containsExactly(
            new LanScannerRestController.ScannableLanDto("apalveien5", "Apalveien 5", "192.168.3.0/24"),
            new LanScannerRestController.ScannableLanDto("Vaier server", "Vaier server", "172.31.0.0/16"));
    }

    @Test
    void getReturnsSnapshotWithStatusAndMappedMachines() {
        Instant completed = Instant.parse("2026-06-04T12:00:00Z");
        when(getDiscoveredLanMachinesUseCase.snapshot()).thenReturn(new LanScanSnapshot(
            ScanStatus.IDLE,
            List.of(new DiscoveredLanMachine("192.168.3.10", "docker01", List.of(2375, 22), "apalveien5")),
            completed));

        ResponseEntity<LanScanResponse> response = controller.getSnapshot();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().status()).isEqualTo("IDLE");
        assertThat(response.getBody().lastScanCompleted()).isEqualTo("2026-06-04T12:00:00Z");
        assertThat(response.getBody().machines()).containsExactly(new DiscoveredMachineDto(
            "192.168.3.10", "docker01", List.of(2375, 22), "DOCKER_HOST", "apalveien5", "SERVER",
            true, false, "apalveien5|192.168.3.10"));
    }

    @Test
    void getCarriesSshAvailableFromWhetherPort22IsOpen() {
        when(getDiscoveredLanMachinesUseCase.snapshot()).thenReturn(new LanScanSnapshot(
            ScanStatus.IDLE,
            List.of(new DiscoveredLanMachine("192.168.3.10", "docker01", List.of(2375, 22), "apalveien5"),
                    new DiscoveredLanMachine("192.168.3.20", "printer", List.of(9100), "apalveien5")),
            Instant.parse("2026-06-04T12:00:00Z")));

        var machines = controller.getSnapshot().getBody().machines();

        assertThat(machines.get(0).sshAvailable()).isTrue();   // 22 open
        assertThat(machines.get(1).sshAvailable()).isFalse();  // 22 absent
    }

    @Test
    void getCarriesTheIgnoredFlagAndIgnoreKey() {
        when(getDiscoveredLanMachinesUseCase.snapshot()).thenReturn(new LanScanSnapshot(
            ScanStatus.IDLE,
            List.of(new DiscoveredLanMachine("192.168.3.111", "printer", List.of(9100), "apalveien5", true)),
            Instant.parse("2026-06-04T12:00:00Z")));

        var machine = controller.getSnapshot().getBody().machines().get(0);

        assertThat(machine.ignored()).isTrue();
        assertThat(machine.ignoreKey()).isEqualTo("apalveien5|192.168.3.111");
    }

    @Test
    void ignoreEndpointCallsTheUseCaseAndReturns204() {
        ResponseEntity<Void> response = controller.ignore(new IgnoreRequest("apalveien5|192.168.3.111"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(ignoreLanMachineUseCase).ignore("apalveien5|192.168.3.111");
    }

    @Test
    void unignoreEndpointCallsTheUseCaseAndReturns204() {
        ResponseEntity<Void> response = controller.unignore(new IgnoreRequest("apalveien5|192.168.3.111"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(unignoreLanMachineUseCase).unignore("apalveien5|192.168.3.111");
    }

    @Test
    void getDerivesDeviceCategoryFromHostnameThenRole() {
        // Hostname keyword "synology" wins over the WEB_UI role guess from port 443.
        when(getDiscoveredLanMachinesUseCase.snapshot()).thenReturn(new LanScanSnapshot(
            ScanStatus.IDLE,
            List.of(new DiscoveredLanMachine("192.168.3.11", "my-synology", List.of(443), "apalveien5")),
            Instant.parse("2026-06-04T12:00:00Z")));

        var machine = controller.getSnapshot().getBody().machines().get(0);

        assertThat(machine.deviceCategory()).isEqualTo("NAS");
    }

    @Test
    void getDeviceCategoryFallsBackToRoleWhenHostnameHasNoKeyword() {
        when(getDiscoveredLanMachinesUseCase.snapshot()).thenReturn(new LanScanSnapshot(
            ScanStatus.IDLE,
            List.of(new DiscoveredLanMachine("192.168.3.12", "box-9", List.of(9100), "apalveien5")),
            Instant.parse("2026-06-04T12:00:00Z")));

        var machine = controller.getSnapshot().getBody().machines().get(0);

        assertThat(machine.deviceCategory()).isEqualTo("PRINTER");
    }

    @Test
    void getDeviceCategoryFallsBackToGenericWhenNoSignal() {
        // Unknown role (no telling ports) and no hostname keyword -> GENERIC (LAN role contributes none).
        when(getDiscoveredLanMachinesUseCase.snapshot()).thenReturn(new LanScanSnapshot(
            ScanStatus.IDLE,
            List.of(new DiscoveredLanMachine("192.168.3.13", "box-9", List.of(12345), "apalveien5")),
            Instant.parse("2026-06-04T12:00:00Z")));

        var machine = controller.getSnapshot().getBody().machines().get(0);

        assertThat(machine.deviceCategory()).isEqualTo("GENERIC");
    }

    @Test
    void adoptDelegatesTheIpAndNameOverrideAndReturnsTheCreatedMachine() {
        when(adoptDiscoveredMachineUseCase.adopt("192.168.3.50", "living-room-nas")).thenReturn(
            new LanServer("living-room-nas", "192.168.3.50", true, 2375, null, DeviceCategory.NAS));

        ResponseEntity<AdoptResponse> response =
            controller.adopt("192.168.3.50", new AdoptRequest("living-room-nas"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(adoptDiscoveredMachineUseCase).adopt("192.168.3.50", "living-room-nas");
        AdoptResponse body = response.getBody();
        assertThat(body.name()).isEqualTo("living-room-nas");
        assertThat(body.lanAddress()).isEqualTo("192.168.3.50");
        assertThat(body.runsDocker()).isTrue();
        assertThat(body.dockerPort()).isEqualTo(2375);
        assertThat(body.deviceCategory()).isEqualTo("NAS");
    }

    @Test
    void adoptWithNoBodyPassesANullNameOverride() {
        when(adoptDiscoveredMachineUseCase.adopt("192.168.3.20", null)).thenReturn(
            new LanServer("epson-printer", "192.168.3.20", false, null, null, DeviceCategory.PRINTER));

        ResponseEntity<AdoptResponse> response = controller.adopt("192.168.3.20", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(adoptDiscoveredMachineUseCase).adopt("192.168.3.20", null);
        assertThat(response.getBody().name()).isEqualTo("epson-printer");
    }

    // --- slice 2: pre-registration SSH credential test + adopt with a credential ---

    @Test
    void testSshCredential_returnsTheRedactedVerificationResult_andNeverEchoesTheSecret() {
        when(verifySshCredentialUseCase.verify(eq("192.168.3.50"), anyInt(), any()))
            .thenReturn(new SshCredentialVerification(true, true, "SHA256:abc"));

        ResponseEntity<LanScannerRestController.SshCredentialTestResponse> response =
            controller.testSshCredential("192.168.3.50",
                new SshCredentialTestRequest("root", "PASSWORD", "pw", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = response.getBody();
        assertThat(body.reachable()).isTrue();
        assertThat(body.authenticated()).isTrue();
        assertThat(body.fingerprint()).isEqualTo("SHA256:abc");
        // The response shape structurally cannot carry the secret or passphrase.
        assertThat(Arrays.stream(body.getClass().getRecordComponents()).map(RecordComponent::getName))
            .doesNotContain("secret", "passphrase");
        // The draft handed to the use case carried the secret; it just never rides the response.
        ArgumentCaptor<SshCredentialDraft> draft = ArgumentCaptor.forClass(SshCredentialDraft.class);
        verify(verifySshCredentialUseCase).verify(eq("192.168.3.50"), anyInt(), draft.capture());
        assertThat(draft.getValue()).isEqualTo(
            new SshCredentialDraft("root", AuthMethod.PASSWORD, "pw", null));
    }

    @Test
    void adopt_withCredentialBlock_passesTheDraftAndReportsTheCredentialOutcome() {
        LanServer created = new LanServer("nas", "192.168.3.50", true, 2375, null, DeviceCategory.NAS);
        when(adoptDiscoveredMachineUseCase.adopt(eq("192.168.3.50"), eq("nas"), any())).thenReturn(
            new AdoptionOutcome(created, new SshCredentialVerification(true, true, "SHA256:abc"), true));

        ResponseEntity<AdoptResponse> response = controller.adopt("192.168.3.50",
            new AdoptRequest("nas", new SshCredentialBlock("root", "PASSWORD", "pw", null)));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<SshCredentialDraft> draft = ArgumentCaptor.forClass(SshCredentialDraft.class);
        verify(adoptDiscoveredMachineUseCase).adopt(eq("192.168.3.50"), eq("nas"), draft.capture());
        assertThat(draft.getValue()).isEqualTo(
            new SshCredentialDraft("root", AuthMethod.PASSWORD, "pw", null));
        AdoptResponse body = response.getBody();
        assertThat(body.name()).isEqualTo("nas");
        assertThat(body.credentialProvided()).isTrue();
        assertThat(body.credentialVerified()).isTrue();
        assertThat(body.credentialStored()).isTrue();
    }

    @Test
    void adopt_withCredentialRejected_stillReturnsTheMachineButFlagsTheCredentialUnstored() {
        LanServer created = new LanServer("nas", "192.168.3.50", true, 2375, null, DeviceCategory.NAS);
        when(adoptDiscoveredMachineUseCase.adopt(eq("192.168.3.50"), eq("nas"), any())).thenReturn(
            new AdoptionOutcome(created, new SshCredentialVerification(true, false, null), false));

        AdoptResponse body = controller.adopt("192.168.3.50",
            new AdoptRequest("nas", new SshCredentialBlock("root", "PASSWORD", "wrong", null))).getBody();

        assertThat(body.name()).isEqualTo("nas");
        assertThat(body.credentialProvided()).isTrue();
        assertThat(body.credentialVerified()).isFalse();
        assertThat(body.credentialStored()).isFalse();
    }

    @Test
    void adopt_withNoCredentialBlock_usesThePlainAdoptAndReportsNoCredential() {
        when(adoptDiscoveredMachineUseCase.adopt("192.168.3.50", "nas")).thenReturn(
            new LanServer("nas", "192.168.3.50", true, 2375, null, DeviceCategory.NAS));

        AdoptResponse body =
            controller.adopt("192.168.3.50", new AdoptRequest("nas")).getBody();

        verify(adoptDiscoveredMachineUseCase).adopt("192.168.3.50", "nas");
        assertThat(body.credentialProvided()).isFalse();
        assertThat(body.credentialVerified()).isFalse();
        assertThat(body.credentialStored()).isFalse();
    }

    @Test
    void getReportsScanningWithNoCompletionTimeBeforeTheFirstScan() {
        when(getDiscoveredLanMachinesUseCase.snapshot()).thenReturn(
            new LanScanSnapshot(ScanStatus.SCANNING, List.of(), null));

        LanScanResponse body = controller.getSnapshot().getBody();

        assertThat(body.status()).isEqualTo("SCANNING");
        assertThat(body.lastScanCompleted()).isNull();
        assertThat(body.machines()).isEmpty();
    }
}
