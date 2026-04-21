package net.vaier.rest;

import net.vaier.application.DiscoverLocalContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase.PeerContainers;
import net.vaier.application.GetServerInfoUseCase;
import net.vaier.domain.DockerService;
import net.vaier.domain.Server;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/docker-services")
public class DockerServiceRestController {

    private final GetServerInfoUseCase getServerInfoUseCase;
    private final DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    private final DiscoverLocalContainersUseCase discoverLocalContainersUseCase;

    public DockerServiceRestController(GetServerInfoUseCase getServerInfoUseCase,
                                       DiscoverPeerContainersUseCase discoverPeerContainersUseCase,
                                       DiscoverLocalContainersUseCase discoverLocalContainersUseCase) {
        this.getServerInfoUseCase = getServerInfoUseCase;
        this.discoverPeerContainersUseCase = discoverPeerContainersUseCase;
        this.discoverLocalContainersUseCase = discoverLocalContainersUseCase;
    }

    @GetMapping
    public ResponseEntity<List<DockerService>> getDockerServices(
        @RequestParam String address,
        @RequestParam(required = false) Integer port,
        @RequestParam(defaultValue = "false") boolean tlsEnabled
    ) {
        try {
            Server server = new Server(address, port, tlsEnabled);
            return ResponseEntity.ok(getServerInfoUseCase.getServicesWithExposedPorts(server));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/local")
    public ResponseEntity<List<DockerService>> discoverLocalContainers() {
        try {
            return ResponseEntity.ok(discoverLocalContainersUseCase.discover());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/peers")
    public ResponseEntity<List<PeerContainers>> discoverPeerContainers() {
        try {
            return ResponseEntity.ok(discoverPeerContainersUseCase.discoverAll());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
