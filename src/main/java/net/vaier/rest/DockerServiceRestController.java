package net.vaier.rest;

import net.vaier.application.DiscoverLocalContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase.PeerContainers;
import net.vaier.application.GetServerInfoUseCase;
import net.vaier.domain.DockerService;
import net.vaier.domain.Server;
import java.util.List;
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
    public List<DockerService> getDockerServices(
        @RequestParam String address,
        @RequestParam(required = false) Integer port,
        @RequestParam(defaultValue = "false") boolean tlsEnabled
    ) {
        Server server = new Server(address, port, tlsEnabled);
        return getServerInfoUseCase.getServicesWithExposedPorts(server);
    }

    @GetMapping("/local")
    public List<DockerService> discoverLocalContainers() {
        return discoverLocalContainersUseCase.discover();
    }

    @GetMapping("/peers")
    public List<PeerContainers> discoverPeerContainers() {
        return discoverPeerContainersUseCase.discoverAll();
    }
}
