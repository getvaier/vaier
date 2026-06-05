package net.vaier.rest;

import net.vaier.application.GetDiscoveredLanMachinesUseCase;
import net.vaier.application.GetDiscoveredLanMachinesUseCase.LanScanSnapshot;
import net.vaier.application.GetDiscoveredLanMachinesUseCase.ScanStatus;
import net.vaier.application.ScanLanUseCase;
import net.vaier.domain.DiscoveredLanMachine;
import net.vaier.rest.LanScannerRestController.DiscoveredMachineDto;
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
            "192.168.3.10", "docker01", List.of(2375, 22), "DOCKER_HOST", "apalveien5"));
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
