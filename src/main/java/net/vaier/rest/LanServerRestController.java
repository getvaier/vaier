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
import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.vaier.domain.MachineStatus;
import net.vaier.domain.Reachability;
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
    private final DeleteLanServerUseCase deleteLanServerUseCase;
    private final GetLanServersUseCase getLanServersUseCase;
    private final GetLanServerReachabilityUseCase reachabilityUseCase;
    private final GetLanServerScrapeUseCase getLanServerScrapeUseCase;
    private final ResolveLanAnchorUseCase resolveLanAnchorUseCase;
    private final GenerateLanServerSetupScriptUseCase generateLanServerSetupScriptUseCase;

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
        registerLanServerUseCase.register(
            request.name(), request.lanAddress(), request.runsDocker(), request.dockerPort(),
            request.description());
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

    record RegisterRequest(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                           String description) {}

    record RenameRequest(String newName) {}

    record UpdateDescriptionRequest(String description) {}

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
        Long lastSeen
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
                lastSeen);
        }
    }
}
