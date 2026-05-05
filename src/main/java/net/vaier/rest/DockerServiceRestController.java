package net.vaier.rest;

import net.vaier.application.DiscoverLanServerContainersUseCase;
import net.vaier.application.DiscoverLanServerContainersUseCase.LanServerContainers;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
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
    private final DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase;
    private final DiscoverLanServerContainersUseCase discoverLanServerContainersUseCase;

    public DockerServiceRestController(GetServerInfoUseCase getServerInfoUseCase,
                                       DiscoverPeerContainersUseCase discoverPeerContainersUseCase,
                                       DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase,
                                       DiscoverLanServerContainersUseCase discoverLanServerContainersUseCase) {
        this.getServerInfoUseCase = getServerInfoUseCase;
        this.discoverPeerContainersUseCase = discoverPeerContainersUseCase;
        this.discoverVaierServerContainersUseCase = discoverVaierServerContainersUseCase;
        this.discoverLanServerContainersUseCase = discoverLanServerContainersUseCase;
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

    @GetMapping("/vaier-server")
    public ResponseEntity<List<DockerService>> discoverVaierServerContainers() {
        try {
            return ResponseEntity.ok(discoverVaierServerContainersUseCase.discover());
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

    @GetMapping("/lan-servers")
    public ResponseEntity<List<LanServerContainers>> discoverLanServerContainers() {
        try {
            return ResponseEntity.ok(discoverLanServerContainersUseCase.discoverAllLanServerContainers());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
