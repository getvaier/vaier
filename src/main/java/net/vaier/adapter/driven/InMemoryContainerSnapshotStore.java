package net.vaier.adapter.driven;

import net.vaier.domain.DockerService;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.PublishableService;
import net.vaier.domain.PublishableService.PublishableSource;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.ScopedImage;
import net.vaier.domain.UpdateAvailability;
import net.vaier.domain.VaierServerCatalogue;
import net.vaier.domain.port.ForDiscoveringPeerContainers;
import net.vaier.domain.port.ForDiscoveringVaierServerContainers;
import net.vaier.domain.port.ForGettingVaierServerDockerServices;
import net.vaier.domain.port.ForStoringContainerSnapshots;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * In-memory store for the cached container scrapes. Owns the peer- and Vaier-server snapshots and the
 * last image-update sweep's verdicts that used to be {@code volatile} fields on {@code ContainerService} —
 * a {@code *Service} must not implement a driven ({@code For*}) port, so the state and the read-side
 * driven ports moved here. The scrape/sweep use cases in {@code ContainerService} write through
 * {@link ForStoringContainerSnapshots}; consumers read the decorated views through the discovery ports.
 *
 * <p>Mirrors {@link InMemoryLanReachabilityCache}: {@code volatile} fields written from the scrape
 * scheduler and read from request threads.
 */
@Component
public class InMemoryContainerSnapshotStore implements
    ForDiscoveringVaierServerContainers,
    ForDiscoveringPeerContainers,
    ForGettingVaierServerDockerServices,
    ForStoringContainerSnapshots {

    private volatile List<PeerContainers> peerContainersSnapshot = List.of();
    private volatile List<DockerService> vaierServerContainersSnapshot = List.of();
    private volatile Map<ScopedImage, UpdateAvailability> imageUpdateVerdicts = Map.of();

    private final String vaierNetworkName;
    private final String dockerGatewayIp;

    @Autowired
    public InMemoryContainerSnapshotStore() {
        this(System.getenv().getOrDefault("VAIER_NETWORK_NAME", "vaier-network"),
            System.getenv().getOrDefault("VAIER_DOCKER_GATEWAY", "172.20.0.1"));
    }

    public InMemoryContainerSnapshotStore(String vaierNetworkName, String dockerGatewayIp) {
        this.vaierNetworkName = vaierNetworkName;
        this.dockerGatewayIp = dockerGatewayIp;
    }

    // --- read side (driven query ports) ---

    /** Cached Vaier-server scrape, carrying the last sweep's update-available verdicts. */
    @Override
    public List<DockerService> discover() {
        return withUpdateVerdicts(LanAnchor.VAIER_SERVER_NAME, vaierServerContainersSnapshot);
    }

    /** Cached server-peer scrape, each container carrying the last sweep's verdicts. */
    @Override
    public List<PeerContainers> discoverAll() {
        return peerContainersSnapshot.stream()
            .map(peer -> new PeerContainers(peer.peerName(), peer.vpnIp(), peer.status(),
                withUpdateVerdicts(peer.peerName(), peer.containers()),
                peer.wireguardOutdated(), peer.wireguardExpectedImage()))
            .toList();
    }

    @Override
    public List<PublishableService> getUnpublishedVaierServerServices(List<ReverseProxyRoute> existingRoutes) {
        List<PublishableService> result = new ArrayList<>();
        vaierServerContainersSnapshot.forEach(container -> {
            String name = container.containerName();
            if (VaierServerCatalogue.isExcluded(name)) return;

            container.ports().stream()
                .filter(p -> "tcp".equals(p.type()))
                .filter(p -> VaierServerCatalogue.isPublishablePort(name, p.privatePort()))
                .forEach(p -> container.reachableEndpoint(p, vaierNetworkName, dockerGatewayIp)
                    .filter(ep -> !ReverseProxyRoute.hasRouteFor(existingRoutes, ep.address(), ep.port()))
                    .ifPresent(ep -> result.add(new PublishableService(
                        PublishableSource.VAIER_SERVER,
                        null,
                        ep.address(),
                        container.containerName(),
                        ep.port(),
                        VaierServerCatalogue.rootRedirectPath(name),
                        false
                    ))));
        });
        return result;
    }

    /**
     * Decorate the containers of {@code machine} with the last sweep's verdicts. Keyed by
     * {@link ScopedImage} so the mark matches the verdict the sweep settled for THAT machine's copy of
     * the image. An unswept image reads {@link UpdateAvailability#UNKNOWN} — that rule is
     * {@code DockerService}'s, stated once, so the two can never disagree.
     */
    private List<DockerService> withUpdateVerdicts(String machine, List<DockerService> containers) {
        Map<ScopedImage, UpdateAvailability> verdicts = imageUpdateVerdicts;
        if (verdicts.isEmpty()) {
            return containers;
        }
        return containers.stream()
            .map(c -> c.withUpdateAvailability(verdicts.get(new ScopedImage(machine, c.image()))))
            .toList();
    }

    // --- write / owner side ---

    @Override
    public void storePeerContainers(List<PeerContainers> peers) {
        this.peerContainersSnapshot = peers;
    }

    @Override
    public void storeVaierServerContainers(List<DockerService> containers) {
        this.vaierServerContainersSnapshot = containers;
    }

    @Override
    public void storeImageUpdateVerdicts(Map<ScopedImage, UpdateAvailability> verdicts) {
        this.imageUpdateVerdicts = verdicts;
    }

    @Override
    public List<PeerContainers> peerContainers() {
        return peerContainersSnapshot;
    }

    @Override
    public List<DockerService> vaierServerContainers() {
        return vaierServerContainersSnapshot;
    }

    @Override
    public Map<ScopedImage, UpdateAvailability> imageUpdateVerdicts() {
        return imageUpdateVerdicts;
    }
}
