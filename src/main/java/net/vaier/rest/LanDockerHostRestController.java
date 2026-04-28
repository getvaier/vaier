package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteLanDockerHostUseCase;
import net.vaier.application.GetLanDockerHostsUseCase;
import net.vaier.application.GetLanDockerHostsUseCase.LanDockerHostView;
import net.vaier.application.RegisterLanDockerHostUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/lan-docker-hosts")
@RequiredArgsConstructor
@Slf4j
public class LanDockerHostRestController {

    private final RegisterLanDockerHostUseCase registerLanDockerHostUseCase;
    private final DeleteLanDockerHostUseCase deleteLanDockerHostUseCase;
    private final GetLanDockerHostsUseCase getLanDockerHostsUseCase;

    @GetMapping
    public List<LanDockerHostResponse> list() {
        return getLanDockerHostsUseCase.getAll().stream()
            .map(LanDockerHostResponse::from)
            .toList();
    }

    @PostMapping
    public ResponseEntity<Void> register(@RequestBody RegisterRequest request) {
        log.info("Registering LAN Docker host: {} at {}:{}", request.name(), request.hostIp(), request.port());
        try {
            registerLanDockerHostUseCase.register(request.name(), request.hostIp(), request.port());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            log.warn("LAN Docker host registration rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        log.info("Deleting LAN Docker host: {}", name);
        deleteLanDockerHostUseCase.delete(name);
        return ResponseEntity.ok().build();
    }

    record RegisterRequest(String name, String hostIp, int port) {}

    record LanDockerHostResponse(String name, String hostIp, int port, String relayPeerName) {
        static LanDockerHostResponse from(LanDockerHostView view) {
            return new LanDockerHostResponse(
                view.host().name(), view.host().hostIp(), view.host().port(), view.relayPeerName());
        }
    }
}
