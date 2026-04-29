package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteLanServerUseCase;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.GetLanServersUseCase.LanServerView;
import net.vaier.application.RegisterLanServerUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/lan-servers")
@RequiredArgsConstructor
@Slf4j
public class LanServerRestController {

    private final RegisterLanServerUseCase registerLanServerUseCase;
    private final DeleteLanServerUseCase deleteLanServerUseCase;
    private final GetLanServersUseCase getLanServersUseCase;

    @GetMapping
    public List<LanServerResponse> list() {
        return getLanServersUseCase.getAll().stream()
            .map(LanServerResponse::from)
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

    record RegisterRequest(String name, String lanAddress, boolean runsDocker, Integer dockerPort) {}

    record LanServerResponse(
        String name,
        String lanAddress,
        boolean runsDocker,
        Integer dockerPort,
        String relayPeerName
    ) {
        static LanServerResponse from(LanServerView view) {
            return new LanServerResponse(
                view.server().name(),
                view.server().lanAddress(),
                view.server().runsDocker(),
                view.server().dockerPort(),
                view.relayPeerName());
        }
    }
}
