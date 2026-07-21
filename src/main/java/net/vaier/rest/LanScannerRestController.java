package net.vaier.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import net.vaier.application.AdoptDiscoveredMachineUseCase;
import net.vaier.application.GetDiscoveredLanMachinesUseCase;
import net.vaier.application.GetDiscoveredLanMachinesUseCase.LanScanSnapshot;
import net.vaier.application.IgnoreLanMachineUseCase;
import net.vaier.application.ScanLanUseCase;
import net.vaier.application.UnignoreLanMachineUseCase;
import net.vaier.domain.DiscoveredLanMachine;
import net.vaier.domain.LanServer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final IgnoreLanMachineUseCase ignoreLanMachine;
    private final UnignoreLanMachineUseCase unignoreLanMachine;
    private final AdoptDiscoveredMachineUseCase adoptDiscoveredMachine;

    public LanScannerRestController(ScanLanUseCase scanLan,
                                    GetDiscoveredLanMachinesUseCase getDiscoveredLanMachines,
                                    IgnoreLanMachineUseCase ignoreLanMachine,
                                    UnignoreLanMachineUseCase unignoreLanMachine,
                                    AdoptDiscoveredMachineUseCase adoptDiscoveredMachine) {
        this.scanLan = scanLan;
        this.getDiscoveredLanMachines = getDiscoveredLanMachines;
        this.ignoreLanMachine = ignoreLanMachine;
        this.unignoreLanMachine = unignoreLanMachine;
        this.adoptDiscoveredMachine = adoptDiscoveredMachine;
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

    @PostMapping("/{id}/adopt")
    @Operation(summary = "Adopt a discovered machine as a registered LAN server")
    public ResponseEntity<AdoptResponse> adopt(@PathVariable("id") String id,
                                               @RequestBody(required = false) AdoptRequest request) {
        // Thin: the LAN address (the discovered host's IP) identifies the candidate; every other
        // registered field is derived in the domain. Only the optional name override rides in the body.
        String nameOverride = request == null ? null : request.nameOverride();
        LanServer created = adoptDiscoveredMachine.adopt(id, nameOverride);
        return ResponseEntity.ok(AdoptResponse.from(created));
    }

    @PostMapping("/ignore")
    @Operation(summary = "Dismiss a discovered host from the list")
    public ResponseEntity<Void> ignore(@RequestBody IgnoreRequest request) {
        ignoreLanMachine.ignore(request.key());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/unignore")
    @Operation(summary = "Reveal a previously dismissed host")
    public ResponseEntity<Void> unignore(@RequestBody IgnoreRequest request) {
        unignoreLanMachine.unignore(request.key());
        return ResponseEntity.noContent().build();
    }

    /** The scan snapshot the Machines page renders: status, when it last finished, and the hosts. */
    public record LanScanResponse(String status, String lastScanCompleted,
                                  List<DiscoveredMachineDto> machines) {}

    /** Body for ignore/unignore: the discovered host's stable {@code ignoreKey}. */
    public record IgnoreRequest(String key) {}

    /**
     * Body for adopt: an optional {@code nameOverride}. When blank/absent the domain-suggested name
     * (the discovered host's hostname, else its IP) is used. The candidate is identified by the
     * {@code {id}} path segment (its LAN IP address), not the body.
     */
    public record AdoptRequest(String nameOverride) {}

    /**
     * The registered LAN server produced by adoption — the same LAN-server vocabulary the
     * {@code /lan-servers} view uses (name, LAN address, Docker settings, device category). The
     * runtime fields (reachability/status) are not known at adoption time and are omitted; the
     * Machines page reads them from the LAN-servers list once the machine is registered.
     */
    public record AdoptResponse(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                                String description, String deviceCategory,
                                boolean deviceCategoryOverridden) {
        static AdoptResponse from(LanServer s) {
            return new AdoptResponse(s.name(), s.lanAddress(), s.runsDocker(), s.dockerPort(),
                s.description(), s.effectiveDeviceCategory().name(), s.deviceCategoryOverridden());
        }
    }

    /**
     * What the launchpad/machines page renders per discovered host. {@code deviceCategory} is the
     * derived (never persisted) icon hint: {@code DeviceCategory.detect(hostname, null, role)} —
     * hostname keyword first, then the guessed role, then GENERIC. Lets the UI show a device icon
     * per scanned host. {@code ignored} lets the UI group dismissed hosts and {@code ignoreKey} is
     * the stable key it posts back to ignore/unignore.
     */
    public record DiscoveredMachineDto(String ipAddress, String hostname, List<Integer> openPorts,
                                       String role, String relayAnchor, String deviceCategory,
                                       boolean ignored, String ignoreKey) {
        static DiscoveredMachineDto from(DiscoveredLanMachine m) {
            return new DiscoveredMachineDto(m.ipAddress(), m.hostname(), m.openPorts(),
                m.guessedRole().name(), m.relayAnchor(),
                net.vaier.domain.DeviceCategory.detect(m.hostname(), null, m.guessedRole()).name(),
                m.ignored(), m.ignoreKey());
        }
    }
}
