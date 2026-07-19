package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DiscoverLanServerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
import net.vaier.application.GetVaierServerDockerServicesUseCase;
import net.vaier.application.GetServerInfoUseCase;
import net.vaier.domain.PublishableService;
import net.vaier.domain.PublishableService.PublishableSource;
import net.vaier.application.CheckForImageUpdatesUseCase;
import net.vaier.application.RefreshContainerStateUseCase;
import net.vaier.application.SweepImageUpdatesUseCase;
import net.vaier.domain.DockerService;
import net.vaier.domain.ImageUpdateSweep;
import net.vaier.domain.ImageUpdateTracker;
import net.vaier.domain.MachineType;
import net.vaier.domain.UpdateAvailability;
import net.vaier.domain.UpdateCheckFloor;
import net.vaier.domain.UpdateCheckOutcome;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Server;
import net.vaier.domain.VaierServerCatalogue;
import net.vaier.domain.VpnClient;
import net.vaier.domain.WireguardClientImage;
import net.vaier.domain.port.ForDiscoveringLanServerContainers;
import net.vaier.domain.port.ForDiscoveringPeerContainers;
import net.vaier.domain.port.ForDiscoveringVaierServerContainers;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVaierServerDockerServices;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForResolvingPeerNames;
import net.vaier.domain.port.ForResolvingRegistryDigest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ContainerService implements
    DiscoverVaierServerContainersUseCase,
    DiscoverPeerContainersUseCase,
    DiscoverLanServerContainersUseCase,
    GetServerInfoUseCase,
    GetVaierServerDockerServicesUseCase,
    RefreshContainerStateUseCase,
    SweepImageUpdatesUseCase,
    CheckForImageUpdatesUseCase,
    ForDiscoveringVaierServerContainers,
    ForDiscoveringPeerContainers,
    ForDiscoveringLanServerContainers,
    ForGettingVaierServerDockerServices {

    private final ForGettingServerInfo forGettingServerInfo;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForResolvingPeerNames forResolvingPeerNames;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForGettingLanServers forGettingLanServers;
    private final ForResolvingRegistryDigest forResolvingRegistryDigest;
    private final ForPublishingEvents forPublishingEvents;
    private final ImageUpdateTracker imageUpdateTracker;
    private final UpdateCheckFloor updateCheckFloor;
    private final String vaierNetworkName;
    private final String dockerGatewayIp;

    /**
     * Where a settled update-available verdict is pushed. The container payloads already ride this
     * topic/event — {@code DockerEventListener} publishes it when a container changes state, and the Explorer
     * re-reads its containers on it — so a re-checked verdict travels the road that already exists rather than
     * inventing a second one for the same payload.
     */
    private static final String SSE_TOPIC = "published-services";
    private static final String SSE_EVENT = "service-updated";
    private static final String SSE_DATA = "image-updates-checked";

    /**
     * Last peer-container scrape, served by {@link #discoverAll()}. Empty until the first
     * {@link #refresh()} runs (the state-refresh scheduler triggers it shortly after startup).
     */
    private volatile List<PeerContainers> peerContainersSnapshot = List.of();

    /**
     * Last Vaier-server container scrape, served by {@link #discover()} and
     * {@link #getUnpublishedVaierServerServices}. Listing and image-inspecting every container
     * on a busy host is slow enough to be worth keeping off the request thread.
     */
    private volatile List<DockerService> vaierServerContainersSnapshot = List.of();

    /**
     * Last update-available sweep's verdicts, image → verdict, applied to the snapshots as they are served so
     * the Explorer can badge a container without anyone scraping or asking a registry on a request thread.
     * Empty until the first sweep runs, which reads as {@link UpdateAvailability#UNKNOWN} — never as up to
     * date.
     */
    private volatile Map<String, UpdateAvailability> imageUpdateVerdicts = Map.of();

    @Autowired
    public ContainerService(ForGettingServerInfo forGettingServerInfo,
                            ForGettingVpnClients forGettingVpnClients,
                            ForResolvingPeerNames forResolvingPeerNames,
                            ForGettingPeerConfigurations forGettingPeerConfigurations,
                            ForGettingLanServers forGettingLanServers,
                            ForResolvingRegistryDigest forResolvingRegistryDigest,
                            ForPublishingEvents forPublishingEvents,
                            ImageUpdateTracker imageUpdateTracker,
                            Clock clock) {
        this(forGettingServerInfo, forGettingVpnClients, forResolvingPeerNames, forGettingPeerConfigurations,
            forGettingLanServers, forResolvingRegistryDigest, forPublishingEvents, imageUpdateTracker, clock,
            System.getenv().getOrDefault("VAIER_NETWORK_NAME", "vaier-network"),
            System.getenv().getOrDefault("VAIER_DOCKER_GATEWAY", "172.20.0.1"));
    }

    ContainerService(ForGettingServerInfo forGettingServerInfo,
                     ForGettingVpnClients forGettingVpnClients,
                     ForResolvingPeerNames forResolvingPeerNames,
                     ForGettingPeerConfigurations forGettingPeerConfigurations,
                     ForGettingLanServers forGettingLanServers,
                     ForResolvingRegistryDigest forResolvingRegistryDigest,
                     ForPublishingEvents forPublishingEvents,
                     ImageUpdateTracker imageUpdateTracker,
                     Clock clock,
                     String vaierNetworkName,
                     String dockerGatewayIp) {
        this.forGettingServerInfo = forGettingServerInfo;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forResolvingPeerNames = forResolvingPeerNames;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forGettingLanServers = forGettingLanServers;
        this.forResolvingRegistryDigest = forResolvingRegistryDigest;
        this.forPublishingEvents = forPublishingEvents;
        this.imageUpdateTracker = imageUpdateTracker;
        // Owned outright, unlike the tracker, because this service is the only thing that can spend the
        // registries' rate limit on demand — one singleton service, one floor, so the limit is a limit. If a
        // second entry point ever forces a sweep it must share THIS floor (a bean, as the tracker had to
        // become); two floors would each admit a check a minute and quietly double the ceiling.
        this.updateCheckFloor = new UpdateCheckFloor(clock);
        this.vaierNetworkName = vaierNetworkName;
        this.dockerGatewayIp = dockerGatewayIp;
    }

    /** Cache read — backed by {@link #refresh()}; the launchpad never scrapes Docker on-thread. */
    @Override
    public List<DockerService> discover() {
        return withUpdateVerdicts(vaierServerContainersSnapshot);
    }

    /**
     * The scrape as the host reported it, carrying the last sweep's update-available verdicts.
     *
     * <p>Decorating on the way out rather than on the way in is deliberate: the two run on different clocks —
     * the container scrape every 30s, the registry sweep once a day — and a scrape must never be able to
     * silently erase a verdict it knows nothing about. An image the sweep has not judged reads
     * {@link UpdateAvailability#UNKNOWN}, which is the truth.
     */
    private List<DockerService> withUpdateVerdicts(List<DockerService> containers) {
        Map<String, UpdateAvailability> verdicts = imageUpdateVerdicts;
        if (verdicts.isEmpty()) {
            return containers;
        }
        // No default here on purpose: "an unswept image reads unknown" is DockerService's rule, and stating it
        // twice is how the two would one day disagree. A null verdict is normalised by the domain.
        return containers.stream()
            .map(c -> c.withUpdateAvailability(verdicts.get(c.image())))
            .toList();
    }

    /**
     * Ask the registries what they serve now, for every container Vaier can see on its own host and on its
     * server peers — the sweep the daily watcher drives.
     *
     * <p>The service decides nothing here: it reads its snapshots, hands the domain the registry port, and
     * keeps the answer. {@link ImageUpdateSweep} is what judges which images are worth asking about, asks each
     * distinct one exactly once, and rules an unreachable registry unknown rather than outdated.
     *
     * <p>Reads the cached snapshots rather than re-scraping: the sweep asks registries, not hosts, and the
     * scheduler has already refreshed these within the last 30 seconds.
     */
    @Override
    public Map<String, UpdateAvailability> sweepImageUpdates() {
        Map<String, UpdateAvailability> verdicts =
            ImageUpdateSweep.sweep(everyContainerVaierCanSee(), forResolvingRegistryDigest);
        imageUpdateVerdicts = verdicts;
        return verdicts;
    }

    /**
     * The update check the operator asked for: re-scrape, ask the registries afresh, keep the answer, push it.
     *
     * <p>The service decides nothing here either — it sequences four domain calls and passes the ports in.
     * {@link UpdateCheckFloor} rules whether the registries may be asked at all, {@link ImageUpdateSweep}
     * judges the images, {@link ImageUpdateTracker} rules what the check may do to the alert state, and
     * {@link UpdateCheckOutcome} decides what the operator is told and whether anything is worth pushing.
     *
     * <p><b>The scrape comes first, and that ordering is the feature.</b> The local digest is whatever the 30s
     * container scrape last saw, and the operator clicks seconds after pulling — so sweeping the snapshot in
     * hand would read the pre-pull digest and confirm the very mark the button was pressed to clear. Refusing
     * remembered registry answers (the other half, in {@code sweepFresh}) fixes the mirror image of the same
     * bug. Both are needed: they are stale on opposite sides of the comparison.
     *
     * <p>Refused checks cost nothing at all — no scrape, no registry request — because the abuse a floor is
     * there to stop must not simply move from the registries onto the fleet's Docker daemons.
     */
    @Override
    public UpdateCheckOutcome checkForImageUpdates() {
        UpdateCheckFloor.Admission admission = updateCheckFloor.admit();
        if (!admission.admitted()) {
            return UpdateCheckOutcome.coalesced(admission.lastCheckedAt());
        }

        refresh();
        Map<String, UpdateAvailability> before = imageUpdateVerdicts;
        Map<String, UpdateAvailability> after =
            ImageUpdateSweep.sweepFresh(everyContainerVaierCanSee(), forResolvingRegistryDigest);
        imageUpdateVerdicts = after;
        imageUpdateTracker.clearUpToDate(after);

        UpdateCheckOutcome outcome = UpdateCheckOutcome.checked(before, after, admission.lastCheckedAt());
        if (outcome.worthPublishing()) {
            forPublishingEvents.publish(SSE_TOPIC, SSE_EVENT, SSE_DATA);
        }
        return outcome;
    }

    /** The Vaier server's own containers and its server peers'. LAN-server containers are not swept yet. */
    private List<DockerService> everyContainerVaierCanSee() {
        List<DockerService> containers = new ArrayList<>(vaierServerContainersSnapshot);
        peerContainersSnapshot.forEach(peer -> containers.addAll(peer.containers()));
        return containers;
    }

    List<DockerService> scrapeVaierServerContainers() {
        return forGettingServerInfo.getServicesWithExposedPorts(Server.vaierServer());
    }

    @Override
    public List<DockerService> getServicesWithExposedPorts(Server server) {
        return forGettingServerInfo.getServicesWithExposedPorts(server);
    }

    /** Cache read — the launchpad and {@code /docker-services/peers} never scrape on-thread. */
    @Override
    public List<PeerContainers> discoverAll() {
        return peerContainersSnapshot.stream()
            .map(peer -> new PeerContainers(peer.peerName(), peer.vpnIp(), peer.status(),
                withUpdateVerdicts(peer.containers()), peer.wireguardOutdated(), peer.wireguardExpectedImage()))
            .toList();
    }

    @Override
    public void refresh() {
        try {
            peerContainersSnapshot = scrapePeerContainers();
        } catch (Exception e) {
            log.warn("Peer container scrape failed, keeping previous snapshot: {}", e.getMessage());
        }
        try {
            vaierServerContainersSnapshot = scrapeVaierServerContainers();
        } catch (Exception e) {
            log.warn("Vaier-server container scrape failed, keeping previous snapshot: {}", e.getMessage());
        }
    }

    /**
     * Live scrape of every server-peer's Docker daemon over the VPN. Slow, and slow-to-fail
     * for an unreachable peer — only {@link #refresh()} (driven by the scheduler) calls it.
     */
    List<PeerContainers> scrapePeerContainers() {
        List<VpnClient> clients = forGettingVpnClients.getClients();
        List<PeerContainers> results = new ArrayList<>();

        for (VpnClient client : clients) {
            String vpnIp = client.vpnIp();
            String peerName = forResolvingPeerNames.resolvePeerNameByIp(vpnIp);

            MachineType peerType = forGettingPeerConfigurations.getPeerConfigByIp(vpnIp)
                    .map(ForGettingPeerConfigurations.PeerConfiguration::peerType)
                    .orElse(MachineType.UBUNTU_SERVER);

            if (!peerType.isVpnPeer() || !peerType.isServerType()) {
                log.debug("Skipping Docker discovery for non-server peer {} ({}) of type {}", peerName, vpnIp, peerType);
                continue;
            }

            if (!client.isConnected()) {
                log.debug("Skipping Docker discovery for disconnected peer {} ({})", peerName, vpnIp);
                results.add(new PeerContainers(peerName, vpnIp, "UNREACHABLE", List.of(), false, WireguardClientImage.EXPECTED));
                continue;
            }

            try {
                Server server = new Server(vpnIp, 2375, false);
                List<DockerService> containers = forGettingServerInfo.getServicesWithExposedPorts(server);
                log.info("Discovered {} containers on peer {} ({})", containers.size(), peerName, vpnIp);
                results.add(new PeerContainers(peerName, vpnIp, "OK", containers, WireguardClientImage.anyOutdated(containers), WireguardClientImage.EXPECTED));
            } catch (Exception e) {
                log.warn("Failed to query Docker on peer {} ({}): {}", peerName, vpnIp, e.getMessage());
                results.add(new PeerContainers(peerName, vpnIp, "UNREACHABLE", List.of(), false, WireguardClientImage.EXPECTED));
            }
        }

        return results;
    }

    @Override
    public List<LanServerContainers> discoverAllLanServerContainers() {
        return forGettingLanServers.getAll().stream()
            .filter(view -> view.server().runsDocker())
            .map(this::scrapeLanServer)
            .toList();
    }

    @Override
    public LanServerContainers discoverLanServerContainersForHost(String name) {
        LanServerView view = forGettingLanServers.getAll().stream()
            .filter(v -> v.server().name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("LAN server not found: " + name));
        if (!view.server().runsDocker()) {
            throw new IllegalArgumentException(
                "LAN server " + name + " does not run Docker");
        }
        return scrapeLanServer(view);
    }

    private LanServerContainers scrapeLanServer(LanServerView view) {
        var server = view.server();
        if (view.relayPeerName() == null) {
            log.debug("Skipping LAN server {} ({}) — not inside any relay peer's lanCidr nor the server LAN CIDR",
                server.name(), server.lanAddress());
            return new LanServerContainers(server.name(), server.lanAddress(), server.dockerPort(),
                null, "UNREACHABLE", List.of());
        }
        // relayPeerName is either a relay peer (scrape hops through its tunnel + LAN forwarding)
        // or LanAnchor.VAIER_SERVER_NAME (scrape goes straight from the Vaier container, since the
        // address is in the Vaier server's own subnet). The Docker socket target is the same.
        try {
            Server target = new Server(server.lanAddress(), server.dockerPort(), false);
            List<DockerService> containers = forGettingServerInfo.getServicesWithExposedPorts(target);
            log.info("Discovered {} containers on LAN server {} ({}) via {}",
                containers.size(), server.name(), server.lanAddress(), view.relayPeerName());
            return new LanServerContainers(server.name(), server.lanAddress(), server.dockerPort(),
                view.relayPeerName(), "OK", containers);
        } catch (Exception e) {
            log.warn("Failed to query Docker on LAN server {} ({}): {}",
                server.name(), server.lanAddress(), e.getMessage());
            return new LanServerContainers(server.name(), server.lanAddress(), server.dockerPort(),
                view.relayPeerName(), "UNREACHABLE", List.of());
        }
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
}
