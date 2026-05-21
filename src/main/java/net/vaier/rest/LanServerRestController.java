package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteLanServerUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.GetLanServersUseCase.LanServerView;
import net.vaier.application.RegisterLanServerUseCase;
import net.vaier.application.RenameLanServerUseCase;
import net.vaier.application.ResolveLanAnchorUseCase;
import net.vaier.application.UpdateLanServerDescriptionUseCase;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

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
    private final ResolveLanAnchorUseCase resolveLanAnchorUseCase;

    @GetMapping
    public List<LanServerResponse> list() {
        return getLanServersUseCase.getAll().stream()
            .map(view -> LanServerResponse.from(view,
                reachabilityUseCase.getReachability(view.server().lanAddress()).name(),
                reachabilityUseCase.getLastSeenEpochSec(view.server().lanAddress())))
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
            request.name(), request.lanAddress(), request.runsDocker(), request.dockerPort());
        try {
            registerLanServerUseCase.register(
                request.name(), request.lanAddress(), request.runsDocker(), request.dockerPort(),
                request.description());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            log.warn("LAN server registration rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PatchMapping("/{name}")
    public ResponseEntity<Void> rename(@PathVariable String name,
                                       @RequestBody(required = false) RenameRequest request) {
        String newName = request != null ? request.newName() : null;
        log.info("Renaming LAN server {} to {}", name, newName);
        try {
            renameLanServerUseCase.rename(name, newName);
            return ResponseEntity.noContent().build();
        } catch (java.util.NoSuchElementException e) {
            log.warn("LAN server not found for rename: {}", name);
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("LAN server rename conflict: {}", e.getMessage());
            return ResponseEntity.status(409).build();
        } catch (IllegalArgumentException e) {
            log.warn("Bad LAN server rename request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PatchMapping("/{name}/description")
    public ResponseEntity<Void> updateDescription(@PathVariable String name,
                                                  @RequestBody(required = false) UpdateDescriptionRequest request) {
        String description = request != null ? request.description() : null;
        log.info("Updating description for LAN server {}", name);
        try {
            updateLanServerDescriptionUseCase.updateDescription(name, description);
            return ResponseEntity.noContent().build();
        } catch (java.util.NoSuchElementException e) {
            log.warn("LAN server not found for description update: {}", name);
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        log.info("Deleting LAN server: {}", name);
        deleteLanServerUseCase.delete(name);
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "/docker-setup.sh", produces = "application/x-sh")
    public ResponseEntity<Resource> downloadDockerSetupScript() {
        Resource script = new ClassPathResource("scripts/lan-docker-setup.sh");
        long length;
        try {
            length = script.contentLength();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load lan-docker-setup.sh from classpath", e);
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=lan-docker-setup.sh")
            .contentType(MediaType.parseMediaType("application/x-sh"))
            .contentLength(length)
            .body(script);
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
        Long lastSeen
    ) {
        static LanServerResponse from(LanServerView view, String reachability, Long lastSeen) {
            return new LanServerResponse(
                view.server().name(),
                view.server().lanAddress(),
                view.server().runsDocker(),
                view.server().dockerPort(),
                view.server().description(),
                view.relayPeerName(),
                reachability,
                lastSeen);
        }
    }
}
