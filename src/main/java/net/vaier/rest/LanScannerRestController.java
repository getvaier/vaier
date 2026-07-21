package net.vaier.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import net.vaier.application.AdoptDiscoveredMachineUseCase;
import net.vaier.application.GetDiscoveredLanMachinesUseCase;
import net.vaier.application.GetDiscoveredLanMachinesUseCase.LanScanSnapshot;
import net.vaier.application.IgnoreLanMachineUseCase;
import net.vaier.application.ListScannableLansUseCase;
import net.vaier.application.ScanLanAnchorUseCase;
import net.vaier.application.ScanLanUseCase;
import net.vaier.application.UnignoreLanMachineUseCase;
import net.vaier.application.VerifySshCredentialUseCase;
import net.vaier.application.AdoptDiscoveredMachineUseCase.AdoptionOutcome;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.DiscoveredLanMachine;
import net.vaier.domain.LanServer;
import net.vaier.domain.SshCredentialDraft;
import net.vaier.domain.SshCredentialVerification;
import net.vaier.domain.SshTarget;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ScanLanAnchorUseCase scanLanAnchor;
    private final ListScannableLansUseCase listScannableLans;
    private final GetDiscoveredLanMachinesUseCase getDiscoveredLanMachines;
    private final IgnoreLanMachineUseCase ignoreLanMachine;
    private final UnignoreLanMachineUseCase unignoreLanMachine;
    private final AdoptDiscoveredMachineUseCase adoptDiscoveredMachine;
    private final VerifySshCredentialUseCase verifySshCredential;

    public LanScannerRestController(ScanLanUseCase scanLan,
                                    ScanLanAnchorUseCase scanLanAnchor,
                                    ListScannableLansUseCase listScannableLans,
                                    GetDiscoveredLanMachinesUseCase getDiscoveredLanMachines,
                                    IgnoreLanMachineUseCase ignoreLanMachine,
                                    UnignoreLanMachineUseCase unignoreLanMachine,
                                    AdoptDiscoveredMachineUseCase adoptDiscoveredMachine,
                                    VerifySshCredentialUseCase verifySshCredential) {
        this.scanLan = scanLan;
        this.scanLanAnchor = scanLanAnchor;
        this.listScannableLans = listScannableLans;
        this.getDiscoveredLanMachines = getDiscoveredLanMachines;
        this.ignoreLanMachine = ignoreLanMachine;
        this.unignoreLanMachine = unignoreLanMachine;
        this.adoptDiscoveredMachine = adoptDiscoveredMachine;
        this.verifySshCredential = verifySshCredential;
    }

    @PostMapping
    @Operation(summary = "Scan a LAN — one picked LAN (anchor set) or, with no anchor, every LAN")
    public ResponseEntity<Void> startScan(
            @RequestParam(value = "anchor", required = false) String anchor) {
        // The operator picks a LAN first, so the common path targets one anchor; an absent anchor
        // keeps the fleet-wide sweep the Machines page's "Rescan" still uses. An unknown anchor is a
        // 404 from the domain (via GlobalExceptionHandler) — never a silent empty scan.
        if (anchor == null || anchor.isBlank()) {
            scanLan.startScan();
        } else {
            scanLanAnchor.startScan(anchor);
        }
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/lans")
    @Operation(summary = "The LANs an operator can pick to scan (each relay's LAN, plus the server LAN)")
    public ResponseEntity<List<ScannableLanDto>> getLans() {
        List<ScannableLanDto> lans = listScannableLans.scannableLans().stream()
            .map(ScannableLanDto::from)
            .toList();
        return ResponseEntity.ok(lans);
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
        // registered field is derived in the domain. Only the optional name override and an optional
        // SSH credential ride in the body. With a credential the machine is still always registered —
        // the credential is verified and stored separably (the outcome carries whether it stuck).
        String nameOverride = request == null ? null : request.nameOverride();
        SshCredentialBlock credential = request == null ? null : request.credential();
        if (credential == null) {
            return ResponseEntity.ok(AdoptResponse.from(adoptDiscoveredMachine.adopt(id, nameOverride)));
        }
        AdoptionOutcome outcome = adoptDiscoveredMachine.adopt(id, nameOverride, credential.toDraft());
        return ResponseEntity.ok(AdoptResponse.from(outcome));
    }

    @PostMapping("/{id}/ssh-credential/test")
    @Operation(summary = "Test an SSH credential against a discovered host before adopting it")
    public ResponseEntity<SshCredentialTestResponse> testSshCredential(
            @PathVariable("id") String id, @RequestBody SshCredentialTestRequest request) {
        // Thin: {id} is the candidate's LAN IP — the address to reach. The result carries no secret,
        // so it is safe to return as-is; the domain decided whether the credential authenticated.
        SshCredentialVerification result =
            verifySshCredential.verify(id, SshTarget.DEFAULT_PORT, request.toDraft());
        return ResponseEntity.ok(SshCredentialTestResponse.from(result));
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

    /**
     * One LAN the operator can pick to scan: the stable {@code anchor} to scan and filter by, the
     * "via {@code <name>}" display label, and the {@code cidr} that will be swept.
     */
    public record ScannableLanDto(String anchor, String name, String cidr) {
        static ScannableLanDto from(ListScannableLansUseCase.ScannableLan l) {
            return new ScannableLanDto(l.anchor(), l.name(), l.cidr());
        }
    }

    /** Body for ignore/unignore: the discovered host's stable {@code ignoreKey}. */
    public record IgnoreRequest(String key) {}

    /**
     * Body for adopt: an optional {@code nameOverride} and an optional SSH {@code credential} to attach
     * during adoption (slice 2). When the name is blank/absent the domain-suggested name (the discovered
     * host's hostname, else its IP) is used; when the credential is absent the machine is adopted with
     * no SSH credential. The candidate is identified by the {@code {id}} path segment (its LAN IP
     * address), not the body.
     */
    public record AdoptRequest(String nameOverride, SshCredentialBlock credential) {
        /** Back-compat / no-credential form: adopt with only an optional name override. */
        public AdoptRequest(String nameOverride) {
            this(nameOverride, null);
        }
    }

    /**
     * The SSH credential fields an operator supplies to test or attach during adoption — never keyed to
     * a machine yet (the machine may not exist). Maps to the domain {@link SshCredentialDraft}; an
     * invalid {@code authMethod} throws {@code IllegalArgumentException} → 400.
     */
    public record SshCredentialBlock(String username, String authMethod, String secret, String passphrase) {
        SshCredentialDraft toDraft() {
            return new SshCredentialDraft(username, AuthMethod.valueOf(authMethod), secret, passphrase);
        }
    }

    /** Body for the pre-registration credential test — the same fields as {@link SshCredentialBlock}. */
    public record SshCredentialTestRequest(String username, String authMethod, String secret, String passphrase) {
        SshCredentialDraft toDraft() {
            return new SshCredentialDraft(username, AuthMethod.valueOf(authMethod), secret, passphrase);
        }
    }

    /**
     * The redacted result of a pre-registration credential test: whether the host was reachable, whether
     * the credential authenticated, and the host-key fingerprint it presented (for display only —
     * nothing is pinned). Carries no secret by construction.
     */
    public record SshCredentialTestResponse(boolean reachable, boolean authenticated, String fingerprint) {
        static SshCredentialTestResponse from(SshCredentialVerification v) {
            return new SshCredentialTestResponse(v.reachable(), v.authenticated(), v.fingerprint());
        }
    }

    /**
     * The registered LAN server produced by adoption — the same LAN-server vocabulary the
     * {@code /lan-servers} view uses (name, LAN address, Docker settings, device category). The
     * runtime fields (reachability/status) are not known at adoption time and are omitted; the
     * Machines page reads them from the LAN-servers list once the machine is registered.
     *
     * <p>When a credential was attached (slice 2), {@code credentialProvided} is true and the credential
     * outcome flags report whether it was {@code credentialVerified} server-side and {@code
     * credentialStored} in the vault, plus the {@code hostKeyFingerprint} the host presented. Never
     * echoes the secret.
     */
    public record AdoptResponse(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                                String description, String deviceCategory,
                                boolean deviceCategoryOverridden,
                                boolean credentialProvided, boolean credentialVerified,
                                boolean credentialStored, String hostKeyFingerprint) {
        static AdoptResponse from(LanServer s) {
            return new AdoptResponse(s.name(), s.lanAddress(), s.runsDocker(), s.dockerPort(),
                s.description(), s.effectiveDeviceCategory().name(), s.deviceCategoryOverridden(),
                false, false, false, null);
        }

        static AdoptResponse from(AdoptionOutcome outcome) {
            LanServer s = outcome.server();
            SshCredentialVerification v = outcome.credentialVerification();
            return new AdoptResponse(s.name(), s.lanAddress(), s.runsDocker(), s.dockerPort(),
                s.description(), s.effectiveDeviceCategory().name(), s.deviceCategoryOverridden(),
                true, v != null && v.authenticated(), outcome.credentialStored(),
                v == null ? null : v.fingerprint());
        }
    }

    /**
     * What the launchpad/machines page renders per discovered host. {@code deviceCategory} is the
     * derived (never persisted) icon hint: {@code DeviceCategory.detect(hostname, null, role)} —
     * hostname keyword first, then the guessed role, then GENERIC. Lets the UI show a device icon
     * per scanned host. {@code sshAvailable} (port 22 open) tells the adopt sheet whether to offer
     * the SSH-credential fields at all. {@code ignored} lets the UI group dismissed hosts and {@code
     * ignoreKey} is the stable key it posts back to ignore/unignore.
     */
    public record DiscoveredMachineDto(String ipAddress, String hostname, List<Integer> openPorts,
                                       String role, String relayAnchor, String deviceCategory,
                                       boolean sshAvailable, boolean ignored, String ignoreKey) {
        static DiscoveredMachineDto from(DiscoveredLanMachine m) {
            return new DiscoveredMachineDto(m.ipAddress(), m.hostname(), m.openPorts(),
                m.guessedRole().name(), m.relayAnchor(),
                net.vaier.domain.DeviceCategory.detect(m.hostname(), null, m.guessedRole()).name(),
                m.sshAvailable(), m.ignored(), m.ignoreKey());
        }
    }
}
