package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DiscoverLanServerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
import net.vaier.application.GetVaierServerDockerServicesUseCase;
import net.vaier.application.GetServerInfoUseCase;
import net.vaier.domain.PublishableService;
import net.vaier.application.CheckForImageUpdatesUseCase;
import net.vaier.application.RefreshContainerStateUseCase;
import net.vaier.application.SweepImageUpdatesUseCase;
import net.vaier.domain.DockerService;
import net.vaier.domain.ImageUpdateSweep;
import net.vaier.domain.ImageUpdateSweep.MachineContainers;
import net.vaier.domain.ImageUpdateTracker;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.MachineType;
import net.vaier.domain.ScopedImage;
import net.vaier.domain.UpdateAvailability;
import net.vaier.domain.UpdateCheckFloor;
import net.vaier.domain.UpdateCheckOutcome;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Server;
import net.vaier.domain.VpnClient;
import net.vaier.domain.WireguardClientImage;
import net.vaier.domain.port.ForDiscoveringLanServerContainers;
import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.vaier.domain.port.ForDiscoveringPeerContainers;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import net.vaier.domain.port.ForDiscoveringVaierServerContainers;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVaierServerDockerServices;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForResolvingPeerNames;
import net.vaier.domain.port.ForResolvingRegistryDigest;
import net.vaier.domain.port.ForStoringContainerSnapshots;
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
    CheckForImageUpdatesUseCase {

    private final ForGettingServerInfo forGettingServerInfo;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForResolvingPeerNames forResolvingPeerNames;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForResolvingRegistryDigest forResolvingRegistryDigest;
    private final ForPublishingEvents forPublishingEvents;
    private final ImageUpdateTracker imageUpdateTracker;
    private final UpdateCheckFloor updateCheckFloor;
    // The cached scrapes + sweep verdicts now live in InMemoryContainerSnapshotStore (a service must
    // not implement the driven discovery ports); the scrape/sweep use cases here write and read raw
    // through the store, and consumers read the decorated views through the discovery ports.
    private final ForStoringContainerSnapshots snapshotStore;
    private final ForDiscoveringVaierServerContainers vaierServerContainers;
    private final ForDiscoveringPeerContainers peerContainers;
    private final ForGettingVaierServerDockerServices vaierServerDockerServices;
    private final ForDiscoveringLanServerContainers lanServerContainers;

    /**
     * Where a settled update-available verdict is pushed. The container payloads already ride this
     * topic/event — {@code DockerEventListener} publishes it when a container changes state, and the Explorer
     * re-reads its containers on it — so a re-checked verdict travels the road that already exists rather than
     * inventing a second one for the same payload.
     */
    private static final String SSE_TOPIC = "published-services";
    private static final String SSE_EVENT = "service-updated";
    private static final String SSE_DATA = "image-updates-checked";

    public ContainerService(ForGettingServerInfo forGettingServerInfo,
                            ForGettingVpnClients forGettingVpnClients,
                            ForResolvingPeerNames forResolvingPeerNames,
                            ForGettingPeerConfigurations forGettingPeerConfigurations,
                            ForResolvingRegistryDigest forResolvingRegistryDigest,
                            ForPublishingEvents forPublishingEvents,
                            ImageUpdateTracker imageUpdateTracker,
                            Clock clock,
                            ForStoringContainerSnapshots snapshotStore,
                            ForDiscoveringVaierServerContainers vaierServerContainers,
                            ForDiscoveringPeerContainers peerContainers,
                            ForGettingVaierServerDockerServices vaierServerDockerServices,
                            ForDiscoveringLanServerContainers lanServerContainers) {
        this.forGettingServerInfo = forGettingServerInfo;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forResolvingPeerNames = forResolvingPeerNames;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forResolvingRegistryDigest = forResolvingRegistryDigest;
        this.forPublishingEvents = forPublishingEvents;
        this.imageUpdateTracker = imageUpdateTracker;
        // Owned outright, unlike the tracker, because this service is the only thing that can spend the
        // registries' rate limit on demand — one singleton service, one floor, so the limit is a limit. If a
        // second entry point ever forces a sweep it must share THIS floor (a bean, as the tracker had to
        // become); two floors would each admit a check a minute and quietly double the ceiling.
        this.updateCheckFloor = new UpdateCheckFloor(clock);
        // The store adapter backs the write side and the three read ports; Spring resolves each of
        // these interfaces to that single bean (a service depends on ports, never the adapter class).
        this.snapshotStore = snapshotStore;
        this.vaierServerContainers = vaierServerContainers;
        this.peerContainers = peerContainers;
        this.vaierServerDockerServices = vaierServerDockerServices;
        this.lanServerContainers = lanServerContainers;
    }

    /** Cache read — backed by {@link #refresh()}; the launchpad never scrapes Docker on-thread. */
    @Override
    public List<DockerService> discover() {
        return vaierServerContainers.discover();
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
    public Map<ScopedImage, UpdateAvailability> sweepImageUpdates() {
        Map<ScopedImage, UpdateAvailability> verdicts =
            ImageUpdateSweep.sweep(everyContainerVaierCanSee(), forResolvingRegistryDigest);
        snapshotStore.storeImageUpdateVerdicts(verdicts);
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
        Map<ScopedImage, UpdateAvailability> before = snapshotStore.imageUpdateVerdicts();
        Map<ScopedImage, UpdateAvailability> after =
            ImageUpdateSweep.sweepFresh(everyContainerVaierCanSee(), forResolvingRegistryDigest);
        snapshotStore.storeImageUpdateVerdicts(after);
        imageUpdateTracker.clearUpToDate(after);

        UpdateCheckOutcome outcome = UpdateCheckOutcome.checked(before, after, admission.lastCheckedAt());
        if (outcome.worthPublishing()) {
            forPublishingEvents.publish(SSE_TOPIC, SSE_EVENT, SSE_DATA);
        }
        return outcome;
    }

    /**
     * The Vaier server's own containers and its server peers', each group carrying the machine it came from so
     * the sweep can scope every verdict to a host. The Vaier server's containers sit under the reserved name
     * {@link LanAnchor#VAIER_SERVER_NAME}; each peer's under its own peer name. LAN-server containers are not
     * swept yet.
     */
    private List<MachineContainers> everyContainerVaierCanSee() {
        List<MachineContainers> machines = new ArrayList<>();
        machines.add(new MachineContainers(LanAnchor.VAIER_SERVER_NAME, snapshotStore.vaierServerContainers()));
        snapshotStore.peerContainers().forEach(peer ->
            machines.add(new MachineContainers(peer.peerName(), peer.containers())));
        return machines;
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
        return peerContainers.discoverAll();
    }

    @Override
    public void refresh() {
        try {
            snapshotStore.storePeerContainers(scrapePeerContainers());
        } catch (Exception e) {
            log.warn("Peer container scrape failed, keeping previous snapshot: {}", e.getMessage());
        }
        try {
            snapshotStore.storeVaierServerContainers(scrapeVaierServerContainers());
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

    /** Live LAN-server scrape lives in LanServerContainerDiscoveryAdapter; delegate to the port. */
    @Override
    public List<LanServerContainers> discoverAllLanServerContainers() {
        return lanServerContainers.discoverAllLanServerContainers();
    }

    @Override
    public LanServerContainers discoverLanServerContainersForHost(String name) {
        return lanServerContainers.discoverLanServerContainersForHost(name);
    }

    @Override
    public List<PublishableService> getUnpublishedVaierServerServices(List<ReverseProxyRoute> existingRoutes) {
        // The catalogue-driven filtering over the cached Vaier-server snapshot lives in
        // InMemoryContainerSnapshotStore now; this use case delegates to its read port.
        return vaierServerDockerServices.getUnpublishedVaierServerServices(existingRoutes);
    }
}
