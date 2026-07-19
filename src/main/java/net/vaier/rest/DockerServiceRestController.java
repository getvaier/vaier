package net.vaier.rest;

import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import net.vaier.application.CheckForImageUpdatesUseCase;
import net.vaier.application.GetLanServerScrapeUseCase;
import net.vaier.application.GetServerInfoUseCase;
import net.vaier.domain.DockerService;
import net.vaier.domain.MachineStatus;
import net.vaier.domain.Server;
import net.vaier.domain.UpdateCheckOutcome;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final CheckForImageUpdatesUseCase checkForImageUpdatesUseCase;

    public DockerServiceRestController(GetServerInfoUseCase getServerInfoUseCase,
                                       DiscoverPeerContainersUseCase discoverPeerContainersUseCase,
                                       DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase,
                                       GetLanServerScrapeUseCase getLanServerScrapeUseCase,
                                       CheckForImageUpdatesUseCase checkForImageUpdatesUseCase) {
        this.getServerInfoUseCase = getServerInfoUseCase;
        this.discoverPeerContainersUseCase = discoverPeerContainersUseCase;
        this.discoverVaierServerContainersUseCase = discoverVaierServerContainersUseCase;
        this.getLanServerScrapeUseCase = getLanServerScrapeUseCase;
        this.checkForImageUpdatesUseCase = checkForImageUpdatesUseCase;
    }

    @GetMapping
    public ResponseEntity<List<DockerService>> getDockerServices(
        @RequestParam String address,
        @RequestParam(required = false) Integer port,
        @RequestParam(defaultValue = "false") boolean tlsEnabled
    ) {
        Server server = new Server(address, port, tlsEnabled);
        return ResponseEntity.ok(getServerInfoUseCase.getServicesWithExposedPorts(server));
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
        return ResponseEntity.ok(discoverPeerContainersUseCase.discoverAll());
    }

    @GetMapping("/lan-servers")
    public ResponseEntity<List<LanServerContainers>> discoverLanServerContainers() {
        return ResponseEntity.ok(getLanServerScrapeUseCase.getLanServerContainers());
    }

    /**
     * Check the fleet's images against their registries right now, because the operator asked (#57 slice 3).
     *
     * <p>POST, not GET: it really does go and ask every registry, and that is a side effect with a rate limit
     * behind it. It remains the only mutating thing here that touches no container — Vaier still has no
     * endpoint to pull an image or restart anything, and this opens none. It acts on Vaier's own knowledge.
     *
     * <p>Authenticated like every other admin endpoint, via Traefik forward-auth. Nothing to add: it is not on
     * any anonymous path, so an unauthenticated caller cannot spend the fleet's rate limit.
     */
    @PostMapping("/image-updates/check")
    public ResponseEntity<UpdateCheckResponse> checkForImageUpdates() {
        UpdateCheckOutcome outcome = checkForImageUpdatesUseCase.checkForImageUpdates();
        return ResponseEntity.ok(new UpdateCheckResponse(
            outcome.checked(), outcome.changed(), outcome.lastCheckedAt().toString()));
    }

    /**
     * @param checked       whether the registries were really asked, or the check was coalesced by the floor
     * @param changed       whether any verdict moved
     * @param lastCheckedAt when Vaier last really looked, ISO-8601 — the honest answer for a coalesced check
     */
    record UpdateCheckResponse(boolean checked, boolean changed, String lastCheckedAt) {}
}
