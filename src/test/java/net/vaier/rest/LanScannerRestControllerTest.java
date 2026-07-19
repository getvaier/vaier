package net.vaier.rest;

import net.vaier.application.GetDiscoveredLanMachinesUseCase;
import net.vaier.application.GetDiscoveredLanMachinesUseCase.LanScanSnapshot;
import net.vaier.application.GetDiscoveredLanMachinesUseCase.ScanStatus;
import net.vaier.application.IgnoreLanMachineUseCase;
import net.vaier.application.ScanLanUseCase;
import net.vaier.application.UnignoreLanMachineUseCase;
import net.vaier.domain.DiscoveredLanMachine;
import net.vaier.rest.LanScannerRestController.DiscoveredMachineDto;
import net.vaier.rest.LanScannerRestController.IgnoreRequest;
import net.vaier.rest.LanScannerRestController.LanScanResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanScannerRestControllerTest {

    @Mock ScanLanUseCase scanLanUseCase;
    @Mock GetDiscoveredLanMachinesUseCase getDiscoveredLanMachinesUseCase;
    @Mock IgnoreLanMachineUseCase ignoreLanMachineUseCase;
    @Mock UnignoreLanMachineUseCase unignoreLanMachineUseCase;

    @InjectMocks LanScannerRestController controller;

    @Test
    void postTriggersAScanAndReturns202() {
        ResponseEntity<Void> response = controller.startScan();

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(scanLanUseCase).startScan();
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
            false, "apalveien5|192.168.3.10"));
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
    void getReportsScanningWithNoCompletionTimeBeforeTheFirstScan() {
        when(getDiscoveredLanMachinesUseCase.snapshot()).thenReturn(
            new LanScanSnapshot(ScanStatus.SCANNING, List.of(), null));

        LanScanResponse body = controller.getSnapshot().getBody();

        assertThat(body.status()).isEqualTo("SCANNING");
        assertThat(body.lastScanCompleted()).isNull();
        assertThat(body.machines()).isEmpty();
    }
}
