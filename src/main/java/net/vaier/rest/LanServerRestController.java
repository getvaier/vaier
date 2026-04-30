package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteLanServerUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.GetLanServersUseCase.LanServerView;
import net.vaier.application.RegisterLanServerUseCase;
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
    private final DeleteLanServerUseCase deleteLanServerUseCase;
    private final GetLanServersUseCase getLanServersUseCase;
    private final GetLanServerReachabilityUseCase reachabilityUseCase;

    @GetMapping
    public List<LanServerResponse> list() {
        return getLanServersUseCase.getAll().stream()
            .map(view -> LanServerResponse.from(view,
                reachabilityUseCase.getReachability(view.server().name()).name()))
            .toList();
    }

    @PostMapping
    public ResponseEntity<Void> register(@RequestBody RegisterRequest request) {
        log.info("Registering LAN server: {} at {} (runsDocker={}, dockerPort={})",
            request.name(), request.lanAddress(), request.runsDocker(), request.dockerPort());
        try {
            registerLanServerUseCase.register(
                request.name(), request.lanAddress(), request.runsDocker(), request.dockerPort());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            log.warn("LAN server registration rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
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

    record RegisterRequest(String name, String lanAddress, boolean runsDocker, Integer dockerPort) {}

    record LanServerResponse(
        String name,
        String lanAddress,
        boolean runsDocker,
        Integer dockerPort,
        String relayPeerName,
        String reachability
    ) {
        static LanServerResponse from(LanServerView view, String reachability) {
            return new LanServerResponse(
                view.server().name(),
                view.server().lanAddress(),
                view.server().runsDocker(),
                view.server().dockerPort(),
                view.relayPeerName(),
                reachability);
        }
    }
}
