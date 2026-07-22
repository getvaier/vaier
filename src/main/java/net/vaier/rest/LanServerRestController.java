package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteLanServerUseCase;
import net.vaier.application.GenerateLanServerSetupScriptUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetLanServerScrapeUseCase;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.application.RegisterLanServerUseCase;
import net.vaier.application.RenameLanServerUseCase;
import net.vaier.application.ResolveLanAnchorUseCase;
import net.vaier.application.UpdateLanServerDescriptionUseCase;
import net.vaier.application.UpdateLanServerDeviceCategoryUseCase;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.vaier.domain.MachineStatus;
import net.vaier.domain.Reachability;
import net.vaier.domain.SetupToken;
import net.vaier.domain.port.ForVendingSetupTokens;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/lan-servers")
@RequiredArgsConstructor
@Slf4j
public class LanServerRestController {

    private final RegisterLanServerUseCase registerLanServerUseCase;
    private final RenameLanServerUseCase renameLanServerUseCase;
    private final UpdateLanServerDescriptionUseCase updateLanServerDescriptionUseCase;
    private final UpdateLanServerDeviceCategoryUseCase updateLanServerDeviceCategoryUseCase;
    private final DeleteLanServerUseCase deleteLanServerUseCase;
    private final GetLanServersUseCase getLanServersUseCase;
    private final GetLanServerReachabilityUseCase reachabilityUseCase;
    private final GetLanServerScrapeUseCase getLanServerScrapeUseCase;
    private final ResolveLanAnchorUseCase resolveLanAnchorUseCase;
    private final GenerateLanServerSetupScriptUseCase generateLanServerSetupScriptUseCase;
    private final ForVendingSetupTokens forVendingSetupTokens;

    @GetMapping
    public List<LanServerResponse> list() {
        Map<String, String> scrapeStatusByName = getLanServerScrapeUseCase.getLanServerContainers().stream()
            .collect(Collectors.toMap(LanServerContainers::name, LanServerContainers::status));
        return getLanServersUseCase.getAll().stream()
            .map(view -> {
                Reachability reachability = reachabilityUseCase.getReachability(view.server().lanAddress());
                boolean scrapeOk = "OK".equals(scrapeStatusByName.get(view.server().name()));
                MachineStatus status = MachineStatus.forLanServer(
                    reachability != Reachability.UNKNOWN,
                    reachability == Reachability.OK,
                    view.server().runsDocker(),
                    scrapeOk);
                return LanServerResponse.from(view, reachability.name(), status,
                    reachabilityUseCase.getLastSeenEpochSec(view.server().lanAddress()));
            })
            .toList();
    }

    /**
     * What routes packets to {@code address}: a relay peer's lanCidr, or the Vaier server itself
     * (server LAN CIDR). Returns {@code routable=false} when neither covers it. The Add Machine
     * modal calls this so it doesn't reimplement CIDR-containment in JavaScript.
     */
    @GetMapping("/lan-anchor")
    public LanAnchorResponse lanAnchor(@RequestParam(value = "address", required = false) String address) {
        return resolveLanAnchorUseCase.resolveLanAnchor(address)
            .map(a -> new LanAnchorResponse(true, a.name(), a.cidr()))
            .orElseGet(() -> new LanAnchorResponse(false, null, null));
    }

    @PostMapping
    public ResponseEntity<Void> register(@RequestBody RegisterRequest request) {
        log.info("Registering LAN server: {} at {} (runsDocker={}, dockerPort={})",
            LogSafe.forLog(request.name()), LogSafe.forLog(request.lanAddress()),
            request.runsDocker(), request.dockerPort());
        // An invalid deviceCategory value throws IllegalArgumentException -> 400 via the handler.
        registerLanServerUseCase.register(
            request.name(), request.lanAddress(), request.runsDocker(), request.dockerPort(),
            request.description(), DeviceCategory.fromString(request.deviceCategory()));
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{name}")
    public ResponseEntity<Void> rename(@PathVariable String name,
                                       @RequestBody(required = false) RenameRequest request) {
        String newName = request != null ? request.newName() : null;
        log.info("Renaming LAN server {} to {}", LogSafe.forLog(name), LogSafe.forLog(newName));
        // not-found -> 404, name conflict -> 409, bad name -> 400 all render as ApiError
        // via GlobalExceptionHandler (NotFoundException / ConflictException / IllegalArgumentException).
        renameLanServerUseCase.rename(name, newName);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{name}/description")
    public ResponseEntity<Void> updateDescription(@PathVariable String name,
                                                  @RequestBody(required = false) UpdateDescriptionRequest request) {
        String description = request != null ? request.description() : null;
        log.info("Updating description for LAN server {}", LogSafe.forLog(name));
        updateLanServerDescriptionUseCase.updateDescription(name, description);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sets (or, with a blank/null value, clears) the LAN server's device-category override — the
     * icon hint. An invalid category value propagates as {@code IllegalArgumentException} -> 400;
     * an unknown server as {@code NotFoundException} -> 404.
     */
    @PatchMapping("/{name}/device-category")
    public ResponseEntity<Void> updateDeviceCategory(@PathVariable String name,
                                                     @RequestBody(required = false) UpdateDeviceCategoryRequest request) {
        String deviceCategory = request != null ? request.deviceCategory() : null;
        log.info("Updating device category for LAN server {} to {}",
            LogSafe.forLog(name), LogSafe.forLog(deviceCategory));
        updateLanServerDeviceCategoryUseCase.updateDeviceCategory(name, deviceCategory);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        log.info("Deleting LAN server: {}", LogSafe.forLog(name));
        deleteLanServerUseCase.delete(name);
        return ResponseEntity.ok().build();
    }

    /**
     * The single per-host setup script (#249) — opens the Docker API if the host runs Docker and
     * installs routes via its relay peer if it's relay-anchored. 404 when the host is unknown or
     * has nothing to set up; 409 when its relay peer has no LAN address to route via.
     */
    // No `produces` constraint: the success path sets the x-sh content type explicitly, and
    // leaving it off lets error responses (e.g. a 409 ConflictException) render as JSON ApiError
    // instead of failing content negotiation with a 406.
    @GetMapping(value = "/{name}/setup.sh")
    public ResponseEntity<?> downloadSetupScript(@PathVariable String name) {
        // A relay-without-LAN-address conflict propagates as ConflictException -> 409 ApiError;
        // an unknown/empty host stays a body-less 404 (GET of an optional artifact).
        return generateLanServerSetupScriptUseCase.generateSetupScript(name)
            .<ResponseEntity<?>>map(script -> ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + name + "-setup.sh")
                .contentType(MediaType.parseMediaType("application/x-sh"))
                .body(script))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Mints a single-use {@link SetupToken} for {@code name} (admin-gated — NOT on the Traefik
     * whitelist). Its value is embedded in the {@code curl … | sudo sh} one-liner the operator runs
     * on the LAN host to pull and execute the setup script from the anonymous, token-gated
     * {@code GET /{name}/setup?t=} route below. Parity with the peer setup flow (Slice 4b), reusing
     * the same token machinery — the id is simply the LAN server name.
     */
    @PostMapping("/{name}/setup-token")
    public ResponseEntity<?> mintSetupToken(@PathVariable String name) {
        log.info("Minting setup token for LAN server {}", LogSafe.forLog(name));
        // A host with nothing to set up (runs no Docker, anchors no LAN) has no setup script, so there is
        // no command to hand over — 204, and the UI shows "nothing to install" rather than a link that 404s.
        if (generateLanServerSetupScriptUseCase.generateSetupScript(name).isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        SetupToken minted = forVendingSetupTokens.issue(name);
        return ResponseEntity.ok(new SetupTokenResponse(minted.value(), SetupToken.TTL.toSeconds()));
    }

    /**
     * ANONYMOUS, token-gated serve of the LAN host setup script — reached via a surgical Traefik
     * forward-auth exemption for this ONE path ({@code /setup}, not {@code /setup.sh}). A bare host
     * being onboarded has no oauth2 session, exactly like a peer, so the single-use, ~15-min
     * {@link SetupToken} is the only authorization, validated here in Vaier. The script carries no
     * WireGuard secret but DOES reveal LAN topology, so it is never served plainly anonymous.
     *
     * <p>Consume the token FIRST (single-use — a used link is spent), then generate. There is no
     * #202 one-shot budget for LAN servers: the single-use token is the sole gate. Served as
     * {@code text/plain} so {@code curl … | sudo sh} works. NEVER log the token.
     */
    @GetMapping(value = "/{name}/setup")
    public ResponseEntity<?> serveTokenizedSetupScript(
            @PathVariable String name,
            @RequestParam(name = "t", required = false) String token) {
        log.info("Tokenized setup script requested for LAN server {}", LogSafe.forLog(name));
        if (token == null || !forVendingSetupTokens.consume(name, token)) {
            log.warn("Rejected tokenized setup for LAN server {}: missing, invalid, or already-used token",
                LogSafe.forLog(name));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.TEXT_PLAIN)
                .body("This setup link is invalid or has already been used. Regenerate it in Vaier.\n");
        }
        // A relay-without-LAN-address conflict propagates as ConflictException -> 409 ApiError;
        // an unknown/empty host stays a body-less 404 (GET of an optional artifact).
        return generateLanServerSetupScriptUseCase.generateSetupScript(name)
            .<ResponseEntity<?>>map(script -> ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(script))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record SetupTokenResponse(String token, long expiresInSeconds) {}

    record RegisterRequest(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                           String description, String deviceCategory) {}

    record RenameRequest(String newName) {}

    record UpdateDescriptionRequest(String description) {}

    record UpdateDeviceCategoryRequest(String deviceCategory) {}

    /** {@code routedVia} is a relay peer name or "Vaier server"; both null when not routable. */
    record LanAnchorResponse(boolean routable, String routedVia, String cidr) {}

    record LanServerResponse(
        String name,
        String lanAddress,
        boolean runsDocker,
        Integer dockerPort,
        String description,
        String relayPeerName,
        String reachability,
        MachineStatus status,
        Long lastSeen,
        String deviceCategory,
        boolean deviceCategoryOverridden,
        boolean sshAccess
    ) {
        static LanServerResponse from(LanServerView view, String reachability, MachineStatus status, Long lastSeen) {
            return new LanServerResponse(
                view.server().name(),
                view.server().lanAddress(),
                view.server().runsDocker(),
                view.server().dockerPort(),
                view.server().description(),
                view.relayPeerName(),
                reachability,
                status,
                lastSeen,
                view.server().effectiveDeviceCategory().name(),
                view.server().deviceCategoryOverridden(),
                view.server().effectiveSshAccess());
        }
    }
}
