package net.vaier.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import net.vaier.application.GetDiscoveredLanMachinesUseCase;
import net.vaier.application.GetDiscoveredLanMachinesUseCase.LanScanSnapshot;
import net.vaier.application.ScanLanUseCase;
import net.vaier.domain.DiscoveredLanMachine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Enterprise-only endpoint behind issue #246's "Discovered LAN machines". Gated as a whole with
 * {@link RequiresEnterprise}: a Community instance gets {@code 402 Payment Required} from
 * {@link EnterpriseLicenseInterceptor} and never reaches a handler.
 *
 * <p>The scan is on demand and asynchronous: {@code POST /lan-scan} kicks off a background sweep and
 * returns {@code 202 Accepted} immediately; {@code GET /lan-scan} returns the latest snapshot
 * (status + machines + last-completed). The Machines page polls/listens for the
 * {@code lan-scan-updated} SSE event to refresh when a scan finishes.
 */
@RestController
@RequestMapping("/lan-scan")
@RequiresEnterprise
@Tag(name = "LAN scanner", description = "Discover unregistered machines on the relay LANs (Enterprise)")
public class LanScannerRestController {

    private final ScanLanUseCase scanLan;
    private final GetDiscoveredLanMachinesUseCase getDiscoveredLanMachines;

    public LanScannerRestController(ScanLanUseCase scanLan,
                                    GetDiscoveredLanMachinesUseCase getDiscoveredLanMachines) {
        this.scanLan = scanLan;
        this.getDiscoveredLanMachines = getDiscoveredLanMachines;
    }

    @PostMapping
    @Operation(summary = "Start an asynchronous scan of the relay LANs")
    public ResponseEntity<Void> startScan() {
        scanLan.startScan();
        return ResponseEntity.accepted().build();
    }

    @GetMapping
    @Operation(summary = "Latest discovered-machines snapshot (status + results)")
    public ResponseEntity<LanScanResponse> getSnapshot() {
        LanScanSnapshot snapshot = getDiscoveredLanMachines.snapshot();
        List<DiscoveredMachineDto> machines = snapshot.machines().stream()
            .map(DiscoveredMachineDto::from)
            .toList();
        String completed = snapshot.lastScanCompleted() == null
            ? null : snapshot.lastScanCompleted().toString();
        return ResponseEntity.ok(new LanScanResponse(snapshot.status().name(), completed, machines));
    }

    /** The scan snapshot the Machines page renders: status, when it last finished, and the hosts. */
    public record LanScanResponse(String status, String lastScanCompleted,
                                  List<DiscoveredMachineDto> machines) {}

    /** What the launchpad/machines page renders per discovered host. */
    public record DiscoveredMachineDto(String ipAddress, String hostname, List<Integer> openPorts,
                                       String role, String relayAnchor) {
        static DiscoveredMachineDto from(DiscoveredLanMachine m) {
            return new DiscoveredMachineDto(m.ipAddress(), m.hostname(), m.openPorts(),
                m.guessedRole().name(), m.relayAnchor());
        }
    }
}
