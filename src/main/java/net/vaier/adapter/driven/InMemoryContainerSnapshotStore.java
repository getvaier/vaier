package net.vaier.adapter.driven;

import net.vaier.domain.DockerService;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.DockerService.ServiceEndpoint;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.PublishableService;
import net.vaier.domain.PublishableService.PublishableSource;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.ScopedImage;
import net.vaier.domain.UpdateAvailability;
import net.vaier.domain.VaierServerCatalogue;
import net.vaier.domain.port.ForDiscoveringPeerContainers;
import net.vaier.domain.port.ForResolvingVaierServerIdentity;
import net.vaier.domain.port.ForDiscoveringVaierServerContainers;
import net.vaier.domain.port.ForGettingVaierServerDockerServices;
import net.vaier.domain.port.ForStoringContainerSnapshots;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    /** Image strings the last sweep judged to be moving tags — a nightly channel rather than a release. */
    private volatile Set<String> movingTags = Set.of();

    private final String vaierNetworkName;
    private final String dockerGatewayIp;
    private final ForResolvingVaierServerIdentity vaierServerIdentity;

    @Autowired
    public InMemoryContainerSnapshotStore(ForResolvingVaierServerIdentity vaierServerIdentity) {
        this(System.getenv().getOrDefault("VAIER_NETWORK_NAME", "vaier-network"),
            System.getenv().getOrDefault("VAIER_DOCKER_GATEWAY", "172.20.0.1"), vaierServerIdentity);
    }

    public InMemoryContainerSnapshotStore(String vaierNetworkName, String dockerGatewayIp,
                                          ForResolvingVaierServerIdentity vaierServerIdentity) {
        this.vaierNetworkName = vaierNetworkName;
        this.dockerGatewayIp = dockerGatewayIp;
        this.vaierServerIdentity = vaierServerIdentity;
    }

    // --- read side (driven query ports) ---

    /** Cached Vaier-server scrape, carrying the last sweep's update-available verdicts. */
    @Override
    public List<DockerService> discover() {
        return withUpdateVerdicts(vaierServerIdentity.identity().value(), vaierServerContainersSnapshot);
    }

    /** Cached server-peer scrape, each container carrying the last sweep's verdicts. */
    @Override
    public List<PeerContainers> discoverAll() {
        return peerContainersSnapshot.stream()
            .map(peer -> new PeerContainers(peer.machineId(), peer.peerId(), peer.vpnIp(), peer.status(),
                withUpdateVerdicts(peer.machineId(), peer.containers()),
                peer.wireguardOutdated(), peer.wireguardExpectedImage()))
            .toList();
    }

    @Override
    public List<PublishableService> getUnpublishedVaierServerServices(List<ReverseProxyRoute> existingRoutes) {
        List<PublishableService> result = new ArrayList<>();
        vaierServerContainersSnapshot.forEach(container -> {
            String name = container.containerName();
            if (VaierServerCatalogue.isExcluded(name)) return;
            // A stopped container keeps its published bindings and so reaches the scrape since #356.
            // Nothing answers on its port, so it is not a candidate — the domain says what running means.
            if (!container.isRunning()) return;

            container.ports().stream()
                .filter(p -> "tcp".equals(p.type()))
                .filter(p -> VaierServerCatalogue.isPublishablePort(name, p.privatePort()))
                .forEach(p -> firstUnroutedEndpoint(container, p, existingRoutes)
                    .ifPresent(ep -> result.add(new PublishableService(
                        PublishableSource.VAIER_SERVER,
                        // The Vaier server is the one machine that cannot be found by searching a store,
                        // so its identity comes from the port that owns read-and-assign-once.
                        vaierServerIdentity.identity().value(),
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
     * The endpoint a publish of {@code port} would be written with, or empty when the port is unreachable
     * or already routed. Both decisions are the domain's: {@code everyReachableEndpoint} knows the
     * spellings of one service and leads with the preferred one, {@code hasRouteForAny} knows that a route
     * under any of them means published.
     */
    private Optional<ServiceEndpoint> firstUnroutedEndpoint(
            DockerService container, PortMapping port, List<ReverseProxyRoute> existingRoutes) {
        List<ServiceEndpoint> endpoints =
            container.everyReachableEndpoint(port, vaierNetworkName, dockerGatewayIp);
        return ReverseProxyRoute.hasRouteForAny(existingRoutes, endpoints)
            ? Optional.empty()
            : endpoints.stream().findFirst();
    }

    /**
     * Decorate the containers of {@code machine} with the last sweep's verdicts. Keyed by
     * {@link ScopedImage} so the mark matches the verdict the sweep settled for THAT machine's copy of
     * the image. An unswept image reads {@link UpdateAvailability#UNKNOWN} — that rule is
     * {@code DockerService}'s, stated once, so the two can never disagree.
     */
    private List<DockerService> withUpdateVerdicts(String machineId, List<DockerService> containers) {
        Map<ScopedImage, UpdateAvailability> verdicts = imageUpdateVerdicts;
        Set<String> moving = movingTags;
        if (verdicts.isEmpty() && moving.isEmpty()) {
            return containers;
        }
        return containers.stream()
            .map(c -> c.withUpdateAvailability(verdicts.get(new ScopedImage(machineId, c.image())))
                .withMovingTag(moving.contains(c.image())))
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
    public synchronized void storeImageUpdateVerdicts(Map<ScopedImage, UpdateAvailability> verdicts) {
        this.imageUpdateVerdicts = verdicts;
    }

    @Override
    public void storeMovingTags(Set<String> images) {
        this.movingTags = Set.copyOf(images);
    }

    /**
     * Drop one image's remembered verdict, leaving every other one exactly as it was — a copy-on-write
     * replacement, since the map a sweep hands in may be immutable.
     *
     * <p>Synchronized with {@link #storeImageUpdateVerdicts} so the read-copy-write cannot interleave with
     * a sweep storing its results: without it, a sweep finishing mid-forget would be silently reverted to
     * the map this read a moment earlier.
     */
    @Override
    public synchronized void forgetImageUpdateVerdict(ScopedImage image) {
        if (!imageUpdateVerdicts.containsKey(image)) {
            return;
        }
        Map<ScopedImage, UpdateAvailability> remaining = new HashMap<>(imageUpdateVerdicts);
        remaining.remove(image);
        this.imageUpdateVerdicts = Map.copyOf(remaining);
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
