package net.vaier.rest;

import net.vaier.application.DiscoverLanServerContainersUseCase.LanServerContainers;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase.PeerContainers;
import net.vaier.application.GetLanServerScrapeUseCase;
import net.vaier.application.GetServerInfoUseCase;
import net.vaier.domain.DockerService;
import net.vaier.domain.MachineStatus;
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
    private final GetLanServerScrapeUseCase getLanServerScrapeUseCase;

    public DockerServiceRestController(GetServerInfoUseCase getServerInfoUseCase,
                                       DiscoverPeerContainersUseCase discoverPeerContainersUseCase,
                                       DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase,
                                       GetLanServerScrapeUseCase getLanServerScrapeUseCase) {
        this.getServerInfoUseCase = getServerInfoUseCase;
        this.discoverPeerContainersUseCase = discoverPeerContainersUseCase;
        this.discoverVaierServerContainersUseCase = discoverVaierServerContainersUseCase;
        this.getLanServerScrapeUseCase = getLanServerScrapeUseCase;
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
    public ResponseEntity<VaierServerStatusResponse> discoverVaierServerContainers() {
        // Status is the domain's: scrape succeeded → OK, scrape errored → DOWN. The browser maps
        // the enum to a CSS class and never decides what "OK" means.
        try {
            return ResponseEntity.ok(new VaierServerStatusResponse(
                MachineStatus.OK, discoverVaierServerContainersUseCase.discover()));
        } catch (Exception e) {
            return ResponseEntity.ok(new VaierServerStatusResponse(MachineStatus.DOWN, List.of()));
        }
    }

    record VaierServerStatusResponse(MachineStatus status, List<DockerService> containers) {}

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
            return ResponseEntity.ok(getLanServerScrapeUseCase.getLanServerContainers());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
