package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DiscoverLanServerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
import net.vaier.application.GetContainerStandingsUseCase;
import net.vaier.application.GetVaierServerDockerServicesUseCase;
import net.vaier.application.JudgeContainerStandingsUseCase;
import net.vaier.application.GetServerInfoUseCase;
import net.vaier.domain.PublishableService;
import net.vaier.application.CheckForImageUpdatesUseCase;
import net.vaier.application.RefreshContainerStateUseCase;
import net.vaier.application.SweepImageUpdatesUseCase;
import net.vaier.application.UpdateContainerImageUseCase;
import net.vaier.domain.ContainerObservation;
import net.vaier.domain.ContainerStandingTracker;
import net.vaier.domain.ContainerUpdate;
import net.vaier.domain.ContainerUpdate.Settlement;
import net.vaier.domain.DockerCommandAccess;
import net.vaier.domain.DockerService;
import net.vaier.domain.ImageUpdateSweep;
import net.vaier.domain.ImageUpdateSweep.MachineContainers;
import net.vaier.domain.ImageUpdateTracker;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.MachineContainerStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.ScopedImage;
import net.vaier.domain.SshTarget;
import net.vaier.domain.UpdateAvailability;
import net.vaier.domain.UpdateCheckFloor;
import net.vaier.domain.UpdateCheckOutcome;
import net.vaier.domain.ContainerUpdateEligibility;
import net.vaier.domain.VaierServerCatalogue;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Server;
import net.vaier.domain.VpnClient;
import net.vaier.domain.WireguardClientImage;
import net.vaier.domain.port.ForDiscoveringLanServerContainers;
import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.vaier.domain.port.ForDiscoveringPeerContainers;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import net.vaier.domain.port.ForCheckingDockerCommandAccess;
import net.vaier.domain.port.ForDiscoveringVaierServerContainers;
import net.vaier.domain.port.ForGettingLanServerScrape;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVaierServerDockerServices;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingContainerStandings;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForResolvingPeerIds;
import net.vaier.domain.port.ForResolvingRegistryDigest;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForResolvingVaierServerIdentity;
import net.vaier.domain.port.ForRunningSshCommands;
import net.vaier.domain.port.ForStoringContainerSnapshots;
import net.vaier.domain.port.ForTrackingHostKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class ContainerService implements
    DiscoverVaierServerContainersUseCase,
    DiscoverPeerContainersUseCase,
    DiscoverLanServerContainersUseCase,
    GetServerInfoUseCase,
    GetVaierServerDockerServicesUseCase,
    RefreshContainerStateUseCase,
    JudgeContainerStandingsUseCase,
    GetContainerStandingsUseCase,
    SweepImageUpdatesUseCase,
    UpdateContainerImageUseCase,
    CheckForImageUpdatesUseCase {

    private final ForGettingServerInfo forGettingServerInfo;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForResolvingPeerIds forResolvingPeerIds;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForResolvingRegistryDigest forResolvingRegistryDigest;
    private final ForPublishingEvents forPublishingEvents;
    private final ImageUpdateTracker imageUpdateTracker;
    private final UpdateCheckFloor updateCheckFloor;
    private final Clock clock;
    // The cached scrapes + sweep verdicts now live in InMemoryContainerSnapshotStore (a service must
    // not implement the driven discovery ports); the scrape/sweep use cases here write and read raw
    // through the store, and consumers read the decorated views through the discovery ports.
    private final ForStoringContainerSnapshots snapshotStore;
    private final ForDiscoveringVaierServerContainers vaierServerContainers;
    private final ForDiscoveringPeerContainers peerContainers;
    private final ForGettingVaierServerDockerServices vaierServerDockerServices;
    private final ForDiscoveringLanServerContainers lanServerContainers;
    // The Vaier server's own identity — the one machine that appears in no store, so its containers have
    // no other way to be scoped to a host.
    private final ForResolvingVaierServerIdentity vaierServerIdentity;
    // The debounced LAN-server scrape, read rather than re-scraped: an update must be judged against
    // what Vaier already knows, not by going and asking every LAN server while a request thread waits.
    private final ForGettingLanServerScrape lanServerScrape;
    // The SSH path an update travels. Deliberately SSH and not the Docker API: recreating a container
    // through the daemon would mean widening docker-socket-proxy to create and remove.
    private final ForResolvingSshTargets sshTargets;
    private final ForRunningSshCommands sshCommands;
    private final ForTrackingHostKeys hostKeys;
    /** Where an accepted update is carried out, off whatever thread asked for it. */
    private final Executor updateExecutor;
    // What the disk sweep last saw of each machine's Docker access — read at scrape time, so a container
    // on a machine Vaier cannot drive Docker on is never offered an update that would die on it.
    private final ForCheckingDockerCommandAccess dockerAccess;
    /**
     * What was running when Vaier last looked (#356). The domain owns the port call; this service only
     * hands the port in and passes the tracker what each scrape found.
     */
    private final ContainerStandingTracker containerStandings;
    private final ForPersistingContainerStandings containerStandingMemory;

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
     * Where a moved container standing is pushed. The fleet page already holds the {@code vpn-peers}
     * stream open for peer, LAN and disk liveness, so this travels the road that exists — no second
     * connection, no timer, and nothing at all on a fleet where nothing moved.
     */
    private static final String STANDING_SSE_TOPIC = "vpn-peers";
    private static final String STANDING_SSE_EVENT = "container-standing-changed";

    @Autowired
    public ContainerService(ForGettingServerInfo forGettingServerInfo,
                            ForGettingVpnClients forGettingVpnClients,
                            ForResolvingPeerIds forResolvingPeerIds,
                            ForGettingPeerConfigurations forGettingPeerConfigurations,
                            ForResolvingRegistryDigest forResolvingRegistryDigest,
                            ForPublishingEvents forPublishingEvents,
                            ImageUpdateTracker imageUpdateTracker,
                            Clock clock,
                            ForStoringContainerSnapshots snapshotStore,
                            ForDiscoveringVaierServerContainers vaierServerContainers,
                            ForDiscoveringPeerContainers peerContainers,
                            ForGettingVaierServerDockerServices vaierServerDockerServices,
                            ForDiscoveringLanServerContainers lanServerContainers,
                            ForResolvingVaierServerIdentity vaierServerIdentity,
                            ForGettingLanServerScrape lanServerScrape,
                            ForResolvingSshTargets sshTargets,
                            ForRunningSshCommands sshCommands,
                            ForTrackingHostKeys hostKeys,
                            ForCheckingDockerCommandAccess dockerAccess,
                            ForPersistingContainerStandings containerStandings) {
        // A single thread: updates on one Vaier are serialised rather than piling several multi-minute
        // pulls onto a fleet's bandwidth at once, and no request thread ever waits on one.
        this(forGettingServerInfo, forGettingVpnClients, forResolvingPeerIds, forGettingPeerConfigurations,
            forResolvingRegistryDigest, forPublishingEvents, imageUpdateTracker, clock, snapshotStore,
            vaierServerContainers, peerContainers, vaierServerDockerServices, lanServerContainers,
            vaierServerIdentity, lanServerScrape, sshTargets, sshCommands, hostKeys, dockerAccess,
            containerStandings,
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "container-update");
                thread.setDaemon(true);
                return thread;
            }));
    }

    ContainerService(ForGettingServerInfo forGettingServerInfo,
                     ForGettingVpnClients forGettingVpnClients,
                     ForResolvingPeerIds forResolvingPeerIds,
                     ForGettingPeerConfigurations forGettingPeerConfigurations,
                     ForResolvingRegistryDigest forResolvingRegistryDigest,
                     ForPublishingEvents forPublishingEvents,
                     ImageUpdateTracker imageUpdateTracker,
                     Clock clock,
                     ForStoringContainerSnapshots snapshotStore,
                     ForDiscoveringVaierServerContainers vaierServerContainers,
                     ForDiscoveringPeerContainers peerContainers,
                     ForGettingVaierServerDockerServices vaierServerDockerServices,
                     ForDiscoveringLanServerContainers lanServerContainers,
                     ForResolvingVaierServerIdentity vaierServerIdentity,
                     ForGettingLanServerScrape lanServerScrape,
                     ForResolvingSshTargets sshTargets,
                     ForRunningSshCommands sshCommands,
                     ForTrackingHostKeys hostKeys,
                     ForCheckingDockerCommandAccess dockerAccess,
                     ForPersistingContainerStandings containerStandings,
                     Executor updateExecutor) {
        this.forGettingServerInfo = forGettingServerInfo;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forResolvingPeerIds = forResolvingPeerIds;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forResolvingRegistryDigest = forResolvingRegistryDigest;
        this.forPublishingEvents = forPublishingEvents;
        this.imageUpdateTracker = imageUpdateTracker;
        // Owned outright, unlike the tracker, because this service is the only thing that can spend the
        // registries' rate limit on demand — one singleton service, one floor, so the limit is a limit. If a
        // second entry point ever forces a sweep it must share THIS floor (a bean, as the tracker had to
        // become); two floors would each admit a check a minute and quietly double the ceiling.
        this.updateCheckFloor = new UpdateCheckFloor(clock);
        this.clock = clock;
        // The store adapter backs the write side and the three read ports; Spring resolves each of
        // these interfaces to that single bean (a service depends on ports, never the adapter class).
        this.snapshotStore = snapshotStore;
        this.vaierServerContainers = vaierServerContainers;
        this.peerContainers = peerContainers;
        this.vaierServerDockerServices = vaierServerDockerServices;
        this.lanServerContainers = lanServerContainers;
        this.vaierServerIdentity = vaierServerIdentity;
        this.lanServerScrape = lanServerScrape;
        this.sshTargets = sshTargets;
        this.sshCommands = sshCommands;
        this.hostKeys = hostKeys;
        this.dockerAccess = dockerAccess;
        this.containerStandingMemory = containerStandings;
        this.containerStandings = new ContainerStandingTracker(containerStandings);
        this.updateExecutor = updateExecutor;
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
     *
     * <p>Pushes what it found, on the same domain decision the operator's own check uses: only a moved verdict
     * is worth repainting. Without it the alert mail arrives while every open Explorer still shows no mark.
     */
    @Override
    public ImageUpdateSweep.Result sweepImageUpdates() {
        Map<ScopedImage, UpdateAvailability> before = snapshotStore.imageUpdateVerdicts();
        ImageUpdateSweep.Result result =
            ImageUpdateSweep.sweep(everyContainerVaierCanSee(), forResolvingRegistryDigest, clock.instant());
        snapshotStore.storeImageUpdateVerdicts(result.verdicts());
        if (UpdateCheckOutcome.checked(before, result.verdicts(), clock.instant()).worthPublishing()) {
            forPublishingEvents.publish(SSE_TOPIC, SSE_EVENT, SSE_DATA);
        }
        return result;
    }

    /**
     * The update check the operator asked for: re-scrape, ask the registries afresh, keep the answer, push it.
     *
     * <p>The service decides nothing here either — it sequences four domain calls and passes the ports in.
     * {@link UpdateCheckFloor} rules whether the registries may be asked at all, {@link ImageUpdateSweep}
     * judges the images, {@link ImageUpdateTracker} reports which of them just went stale — the check latches
     * exactly as the daily sweep does, so the news is mailed by whichever path learned it and by no other —
     * and {@link UpdateCheckOutcome} decides what the operator is told and whether anything is worth pushing.
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
        ImageUpdateSweep.Result after =
            ImageUpdateSweep.sweepFresh(everyContainerVaierCanSee(), forResolvingRegistryDigest,
                clock.instant());
        snapshotStore.storeImageUpdateVerdicts(after.verdicts());
        List<ScopedImage> newlyOutOfDate = imageUpdateTracker.update(after);

        UpdateCheckOutcome outcome =
            UpdateCheckOutcome.checked(before, after.verdicts(), admission.lastCheckedAt(), newlyOutOfDate);
        if (outcome.worthPublishing()) {
            forPublishingEvents.publish(SSE_TOPIC, SSE_EVENT, SSE_DATA);
        }
        return outcome;
    }

    /**
     * The Vaier server's own containers and its server peers', each group carrying the IDENTITY of the
     * machine it came from so the sweep can scope every verdict to a host. A peer in no machine registry has
     * no identity, so its containers are not swept — there is nothing to scope a verdict to, and scoping it
     * to a name would fold two machines' verdicts together the moment they shared one. LAN-server containers
     * are not swept yet.
     */
    private List<MachineContainers> everyContainerVaierCanSee() {
        List<MachineContainers> machines = new ArrayList<>();
        // Vaier's own stack never reaches the sweep (#353) — the domain says which containers those are,
        // and they are dropped here, before any registry is asked, rather than swept and then hidden.
        machines.add(new MachineContainers(vaierServerIdentity.identity().value(),
            VaierServerCatalogue.sweepable(snapshotStore.vaierServerContainers())));
        snapshotStore.peerContainers().stream()
            .filter(peer -> peer.machineId() != null)
            .forEach(peer -> machines.add(new MachineContainers(peer.machineId(), peer.containers())));
        return machines;
    }

    /**
     * The Vaier server's own containers, each carrying the domain's verdict on whether Vaier may offer to
     * update its image. Judged here, at the one point that knows WHICH machine was scraped: the same
     * container name means Vaier's own stack on this host and the operator's container on any other, and
     * a browser handed an unjudged container would have to work that out for itself.
     */
    List<DockerService> scrapeVaierServerContainers() {
        return ContainerUpdateEligibility.judgeVaierServerContainers(
            forGettingServerInfo.getServicesWithExposedPorts(Server.vaierServer()),
            dockerAccess.accessFor(vaierServerIdentity.identity()));
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
     * What this round's scrape means for every container Vaier watches (#356).
     *
     * <p>The service decides nothing: it turns each scrape — the Vaier server's own stack, every server
     * peer's, every LAN server's — into a {@link ContainerObservation} that says whether the machine
     * actually answered, hands each to the domain, and keeps the verdicts. Whether a stopped container is
     * news, how many misses it takes, and what to forget are all the tracker's.
     *
     * <p>It reads the caches the scheduler has just refreshed rather than scraping again: this is the
     * second thing that reading says, not a second round of asking.
     *
     * <p>Pushes on the same domain decision the update sweep uses — only a moved standing is worth
     * repainting — so a fleet where everything that was up is still up costs an open Explorer nothing.
     */
    @Override
    public List<ContainerStandingTracker.Verdict> judgeContainerStandings() {
        Instant now = clock.instant();
        List<ContainerStandingTracker.Verdict> verdicts = new ArrayList<>();
        verdicts.addAll(containerStandings.observe(
            ContainerObservation.ofVaierServer(vaierServerIdentity.identity(),
                vaierServerContainers.discover()), now));
        peerContainers.discoverAll().forEach(peer ->
            ContainerObservation.of(peer.machineId(), peer.status(), peer.containers())
                .ifPresent(observation -> verdicts.addAll(containerStandings.observe(observation, now))));
        lanServerScrape.getLanServerContainers().forEach(lanServer ->
            ContainerObservation.of(lanServer.machineId(), lanServer.status(), lanServer.containers())
                .ifPresent(observation -> verdicts.addAll(containerStandings.observe(observation, now))));

        if (ContainerStandingTracker.Verdict.worthPublishing(verdicts)) {
            forPublishingEvents.publish(STANDING_SSE_TOPIC, STANDING_SSE_EVENT, "");
        }
        return List.copyOf(verdicts);
    }

    /** Memory read — the standings the rounds above have already worked out. Reaches no machine. */
    @Override
    public List<MachineContainerStanding> getContainerStandings() {
        return containerStandingMemory.all();
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
            String peerId = forResolvingPeerIds.resolvePeerIdByIp(vpnIp);

            var storedConfig = forGettingPeerConfigurations.getPeerConfigByIp(vpnIp);
            MachineType peerType = storedConfig
                    .map(ForGettingPeerConfigurations.PeerConfiguration::peerType)
                    .orElse(MachineType.UBUNTU_SERVER);
            // Read, never minted: a live WireGuard peer with no config on disk is in no machine registry,
            // so it reports no identity rather than a fabricated one that would join to nothing.
            String machineId = storedConfig
                    .map(ForGettingPeerConfigurations.PeerConfiguration::machineId)
                    .map(MachineId::value)
                    .orElse(null);

            if (!peerType.isVpnPeer() || !peerType.isServerType()) {
                log.debug("Skipping Docker discovery for non-server peer {} ({}) of type {}", peerId, vpnIp, peerType);
                continue;
            }

            if (!client.isConnected()) {
                log.debug("Skipping Docker discovery for disconnected peer {} ({})", peerId, vpnIp);
                results.add(new PeerContainers(machineId, peerId, vpnIp, "UNREACHABLE", List.of(), false, WireguardClientImage.EXPECTED));
                continue;
            }

            try {
                Server server = new Server(vpnIp, 2375, false);
                // A peer's containers are the operator's own, whatever they are named — nothing here is
                // Vaier's own stack, so a peer's traefik stays the operator's to update.
                List<DockerService> containers = ContainerUpdateEligibility.judgeOperatorContainers(
                    forGettingServerInfo.getServicesWithExposedPorts(server),
                    // A live peer with no stored config has no identity, so nothing is known about its
                    // Docker access — which reads UNKNOWN, and UNKNOWN keeps the button.
                    machineId == null ? DockerCommandAccess.UNKNOWN
                        : dockerAccess.accessFor(MachineId.of(machineId)));
                log.info("Discovered {} containers on peer {} ({})", containers.size(), peerId, vpnIp);
                results.add(new PeerContainers(machineId, peerId, vpnIp, "OK", containers, WireguardClientImage.anyOutdated(containers), WireguardClientImage.EXPECTED));
            } catch (Exception e) {
                log.warn("Failed to query Docker on peer {} ({}): {}", peerId, vpnIp, e.getMessage());
                results.add(new PeerContainers(machineId, peerId, vpnIp, "UNREACHABLE", List.of(), false, WireguardClientImage.EXPECTED));
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

    /**
     * Update one container to the image its registry now serves (#352).
     *
     * <p>The service decides nothing: it gathers the containers Vaier has scraped from that machine, hands
     * them to {@link ContainerUpdate}, which rules whether the update may happen at all and what compose
     * will be asked to do, resolves the machine's SSH target, and hands the run off.
     *
     * <p><b>Everything that can refuse, refuses before the hand-off.</b> An unknown container, a container
     * Vaier will not recreate and a machine with no stored credential all throw here, on the caller's
     * thread, so the operator gets a 4xx that says which instead of a spinner and a silent event that never
     * arrives. Only what genuinely takes minutes — the pull and the recreate — crosses onto the executor.
     */
    @Override
    public void updateContainerImage(MachineId machineId, String containerName) {
        ContainerUpdate update =
            ContainerUpdate.of(machineId, containerName, containersOn(machineId));
        SshTarget target = sshTargets.resolve(machineId);
        updateExecutor.execute(() -> settle(update, target));
    }

    /**
     * Carry an accepted update out and announce how it ended — always, whatever happened. The domain rules
     * how a failed attempt reads and hands the reason back as data; this only writes that reason to the log,
     * so an operator debugging a failed update can still find the cause, and pushes the settled outcome.
     * Announcing is unconditional on purpose: an event that never comes leaves the Explorer waiting forever.
     */
    private void settle(ContainerUpdate update, SshTarget target) {
        Settlement settlement = update.carryOut(target, sshCommands, hostKeys);
        logSettled(update, settlement);
        // Retire the stale verdict BEFORE announcing: the Explorer re-reads its containers when the settled
        // event arrives, so announcing first would hand it the mark this update just resolved.
        update.forgetOutdatedVerdict(settlement.outcome(), snapshotStore);
        update.announce(settlement, forPublishingEvents);
    }

    /**
     * One line per settled update, naming the machine, the container and the outcome — and the host's own
     * words when there are any.
     *
     * <p>An update recreates a running container on somebody's machine. It is the most destructive thing
     * Vaier does to one, and it left no trace at all: an operator asking their Vaier's log whether an
     * update had even been attempted found nothing, and a failed one said nothing about why. That is the
     * audit trail, not chatter. The level follows the outcome, so a fleet's failed updates can be found
     * without reading its successful ones.
     */
    private void logSettled(ContainerUpdate update, Settlement settlement) {
        String reason = settlement.diagnostic() == null ? "" : " — " + settlement.diagnostic();
        if (settlement.outcome().updated()) {
            log.info("Update of container {} on machine {} settled {}{}", update.containerName(),
                update.machineId().value(), settlement.outcome(), reason);
            // The replaced image is still on the host. Not a failed update — housekeeping that did not
            // happen — so it is said here and never in what the operator is shown.
            if (settlement.replacedImageDiagnostic() != null) {
                log.info("Kept {} on machine {}: {}", update.replacedImageReference().orElse(update.image()),
                    update.machineId().value(), settlement.replacedImageDiagnostic());
            }
        } else {
            log.warn("Update of container {} on machine {} settled {}{}", update.containerName(),
                update.machineId().value(), settlement.outcome(), reason);
        }
    }

    /**
     * Every container Vaier has scraped from the machine {@code machineId} — the Vaier server's own, a
     * server peer's, or a LAN server's. Read from what Vaier already knows rather than scraped afresh: the
     * scheduler refreshes all three every 30 seconds, and an update is not worth making an operator wait
     * on a fleet-wide scrape. A machine Vaier knows nothing about simply has no containers, and the
     * domain's "no container of that name" refusal covers it.
     */
    private List<DockerService> containersOn(MachineId machineId) {
        List<DockerService> containers = new ArrayList<>();
        if (machineId.equals(vaierServerIdentity.identity())) {
            containers.addAll(vaierServerContainers.discover());
        }
        peerContainers.discoverAll().stream()
            .filter(peer -> machineId.value().equals(peer.machineId()))
            .forEach(peer -> containers.addAll(peer.containers()));
        lanServerScrape.getLanServerContainers().stream()
            .filter(lanServer -> machineId.value().equals(lanServer.machineId()))
            .forEach(lanServer -> containers.addAll(lanServer.containers()));
        return containers;
    }

    @Override
    public List<PublishableService> getUnpublishedVaierServerServices(List<ReverseProxyRoute> existingRoutes) {
        // The catalogue-driven filtering over the cached Vaier-server snapshot lives in
        // InMemoryContainerSnapshotStore now; this use case delegates to its read port.
        return vaierServerDockerServices.getUnpublishedVaierServerServices(existingRoutes);
    }
}
