package net.vaier.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.vaier.adapter.driven.InMemoryContainerSnapshotStore;
import net.vaier.adapter.driven.InMemoryDockerCommandAccessCache;
import net.vaier.adapter.driven.InMemoryImageUpdateStateAdapter;
import net.vaier.adapter.driven.LanServerContainerDiscoveryAdapter;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.CommandResult;
import net.vaier.domain.ConflictException;
import net.vaier.domain.ContainerUpdate;
import net.vaier.domain.DockerCommandAccess;
import net.vaier.domain.MachineId;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PeerId;
import net.vaier.domain.SshTarget;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import net.vaier.domain.port.ForGettingLanServerScrape;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForRunningSshCommands;
import net.vaier.domain.port.ForTrackingHostKeys;
import net.vaier.domain.PublishableService;
import net.vaier.domain.PublishableService.PublishableSource;
import net.vaier.domain.LanServer;
import net.vaier.config.ServiceNames;
import net.vaier.domain.DockerService;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.MachineType;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Server;
import net.vaier.domain.VpnClient;
import net.vaier.domain.WireguardClientImage;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForResolvingPeerIds;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForResolvingRegistryDigest;
import net.vaier.domain.ImageUpdateSweep;
import net.vaier.domain.ImageUpdateTracker;
import net.vaier.domain.ScopedImage;
import net.vaier.domain.ComposeCoordinates;
import net.vaier.domain.UpdateAvailability;
import net.vaier.domain.ContainerUpdateEligibility;
import net.vaier.domain.UpdateCheckOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import net.vaier.adapter.driven.InMemoryContainerStandingAdapter;
import net.vaier.domain.ContainerStanding;
import net.vaier.domain.ContainerStandingTracker;
import net.vaier.domain.MachineContainerStanding;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerServiceTest {

    private static final String VAIER_NETWORK = "vaier-network";
    private static final String GATEWAY_IP = "172.20.0.1";

    @Mock ForGettingServerInfo forGettingServerInfo;
    @Mock ForGettingVpnClients forGettingVpnClients;
    @Mock ForResolvingPeerIds forResolvingPeerIds;
    @Mock ForGettingPeerConfigurations forGettingPeerConfigurations;
    @Mock ForGettingLanServers forGettingLanServers;
    @Mock ForResolvingRegistryDigest forResolvingRegistryDigest;
    @Mock ForPublishingEvents forPublishingEvents;
    @Mock ForGettingLanServerScrape forGettingLanServerScrape;
    @Mock ForResolvingSshTargets forResolvingSshTargets;
    @Mock ForRunningSshCommands forRunningSshCommands;
    @Mock ForTrackingHostKeys forTrackingHostKeys;

    ContainerService service;
    // Real cache, as in production: a machine nobody has swept reads UNKNOWN, which keeps the button.
    InMemoryDockerCommandAccessCache dockerAccessCache;
    // The real store, so a test can seed the verdict a pre-#353 Vaier would already be holding.
    InMemoryContainerSnapshotStore snapshotStore;
    // Real memory, as in production: what Vaier has seen running survives a scrape that fails.
    InMemoryContainerStandingAdapter containerStandings;
    ImageUpdateTracker tracker;
    MutableClock clock;
    DeferredExecutor updateExecutor;

    /**
     * Holds what the service hands off instead of running it, so a test can prove the request thread was
     * released before the update ran — and then run it deterministically.
     */
    private static class DeferredExecutor implements Executor {
        private final List<Runnable> queued = new ArrayList<>();

        @Override public void execute(Runnable command) { queued.add(command); }

        int pending() { return queued.size(); }

        void runPending() {
            List<Runnable> toRun = List.copyOf(queued);
            queued.clear();
            toRun.forEach(Runnable::run);
        }
    }

    /** Wind-forward clock, so the update check's 60s floor can be proven without sleeping. */
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-17T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration by) { now = now.plus(by); }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        // The cached snapshots + sweep verdicts live in the store adapter, and the live LAN-server
        // scrape in its own adapter — both real infrastructure fed by the same mocks, so the end-to-end
        // flows behave exactly as in production. One store instance backs the write side and the three
        // read ports.
        snapshotStore = new InMemoryContainerSnapshotStore(
            VAIER_NETWORK, GATEWAY_IP, () -> TestMachineIds.of("Vaier server"));
        // Built after the store, because the tracker publishes the moving tags it works out into it — the
        // same one the containers the Explorer reads are decorated from.
        tracker = new ImageUpdateTracker(new InMemoryImageUpdateStateAdapter(), snapshotStore);
        dockerAccessCache = new InMemoryDockerCommandAccessCache();
        var lanServerDiscovery =
            new LanServerContainerDiscoveryAdapter(forGettingLanServers, forGettingServerInfo, dockerAccessCache);
        updateExecutor = new DeferredExecutor();
        containerStandings = new InMemoryContainerStandingAdapter();
        service = new ContainerService(forGettingServerInfo, forGettingVpnClients,
            forResolvingPeerIds, forGettingPeerConfigurations,
            forResolvingRegistryDigest, forPublishingEvents, tracker, clock,
            snapshotStore, snapshotStore, snapshotStore, snapshotStore, lanServerDiscovery,
            () -> TestMachineIds.of("Vaier server"), forGettingLanServerScrape,
            forResolvingSshTargets, forRunningSshCommands, forTrackingHostKeys, dockerAccessCache,
            containerStandings, updateExecutor);
    }

    // --- Update available (#57) ---

    private static DockerService imaged(String name, String image, String localDigest) {
        return new DockerService("id-" + name, name, image, "v",
            List.of(new PortMapping(80, 8080, "tcp", "0.0.0.0")), List.of(VAIER_NETWORK), "running",
            localDigest, UpdateAvailability.UNKNOWN);
    }

    /** A sweep that ruled {@code image} update available and resolved no registry answer. */
    private ImageUpdateSweep.Result judged(ScopedImage image) {
        return new ImageUpdateSweep.Result(Map.of(image, UpdateAvailability.UPDATE_AVAILABLE),
            Map.of(), clock.instant());
    }

    /** An image on the Vaier server — the machine the local-container scrape reports under. */
    private static ScopedImage onVaierServer(String image) {
        return new ScopedImage(TestMachineIds.of("Vaier server").value(), image);
    }

    @Test
    void sweepImageUpdates_judgesVaierServerContainersAgainstTheRegistry() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forResolvingRegistryDigest.resolveDigest(any())).thenReturn(Optional.of("sha256:new"));
        service.refresh();

        assertThat(service.sweepImageUpdates().verdicts())
            .containsEntry(onVaierServer("vaultwarden/server:latest"), UpdateAvailability.UPDATE_AVAILABLE);
    }

    @Test
    void discover_decoratesTheSnapshotWithTheLastSweepsVerdict() {
        // The flag must reach the REST payload so the Explorer can badge it — without a second scrape.
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forResolvingRegistryDigest.resolveDigest(any())).thenReturn(Optional.of("sha256:new"));
        service.refresh();
        service.sweepImageUpdates();

        assertThat(service.discover()).singleElement()
            .extracting(DockerService::updateAvailable).isEqualTo(UpdateAvailability.UPDATE_AVAILABLE);
    }

    @Test
    void sweepKeysVaierServerContainersUnderTheVaierServerName_andTheUiMarkStillLightsUp() {
        // #57 refinement: the Vaier server's own containers are scoped under LanAnchor.VAIER_SERVER_NAME. The
        // sweep's key and discover()'s decoration key must be the same ScopedImage, or the mark would never
        // light up for a local container.
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forResolvingRegistryDigest.resolveDigest(any())).thenReturn(Optional.of("sha256:new"));
        service.refresh();

        assertThat(service.sweepImageUpdates().verdicts())
            .containsKey(onVaierServer("vaultwarden/server:latest"));
        assertThat(service.discover()).singleElement()
            .extracting(DockerService::updateAvailable).isEqualTo(UpdateAvailability.UPDATE_AVAILABLE);
    }

    @Test
    void discover_reportsUnknownBeforeAnySweepHasRun() {
        // A scrape reads the host; only the sweep asks the registry. Un-swept is unknown, never up to date.
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        service.refresh();

        assertThat(service.discover()).singleElement()
            .extracting(DockerService::updateAvailable).isEqualTo(UpdateAvailability.UNKNOWN);
        verify(forResolvingRegistryDigest, never()).resolveDigest(any());
    }

    @Test
    void sweepImageUpdates_coversPeerContainersToo() {
        // The #57 incident was a peer's container: apalveien5's vaultwarden.
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s != null && s.dockerHostUrl().equals("unix:///var/run/docker.sock"))))
            .thenReturn(List.of());
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.6/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.6")).thenReturn("Apalveien 5");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.6"))
            .thenReturn(Optional.of(peerConfig("Apalveien 5", "10.13.13.6", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(argThat(s -> s != null && "10.13.13.6".equals(s.getAddress()))))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forResolvingRegistryDigest.resolveDigest(any())).thenReturn(Optional.of("sha256:new"));
        service.refresh();

        assertThat(service.sweepImageUpdates().verdicts())
            .containsEntry(new ScopedImage(TestMachineIds.of("Apalveien 5").value(),
                    "vaultwarden/server:latest"),
                UpdateAvailability.UPDATE_AVAILABLE);
    }

    @Test
    void threeDailySweepsOnANightlyMarkTheContainerAsAMovingTag_andMailNoSecondTime() {
        // The wiring, end to end on the daily path: the sweep resolves the digests, the tracker (as the
        // watcher folds it) works out the rhythm and publishes it, and the container the Explorer reads
        // carries the word. Colina's netdata is the case — a new nightly every night, mailed every morning.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("netdata", "netdata/netdata:latest", "sha256:n0")));
        when(forResolvingRegistryDigest.resolveDigest(any()))
            .thenReturn(Optional.of("sha256:n1"), Optional.of("sha256:n2"), Optional.of("sha256:n3"));

        service.refresh();
        assertThat(tracker.update(service.sweepImageUpdates())).as("the first change is news").hasSize(1);
        tracker.update(service.sweepImageUpdates());
        assertThat(tracker.update(service.sweepImageUpdates()))
            .as("a channel by now — the mark stays, the mail stops").isEmpty();

        assertThat(service.discover()).singleElement()
            .satisfies(c -> {
                assertThat(c.updateAvailable()).isEqualTo(UpdateAvailability.UPDATE_AVAILABLE);
                assertThat(c.movingTag()).isTrue();
            });
    }

    // --- #57 slice 3: the check the operator asked for ---

    @Test
    void checkForImageUpdates_reScrapesTheContainersBeforeSweeping_soItReadsThePostPullDigest() {
        // The 30s trap. The local digest comes from the container scrape, and the operator clicks SECONDS
        // after pulling — so the snapshot in hand is very likely the pre-pull one. Sweeping that would compare
        // yesterday's local digest against a fresh registry answer and report the update they had just
        // applied as still pending: the button confirming the mark it was pressed to clear.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:new")));
        when(forResolvingRegistryDigest.resolveDigestNow(any())).thenReturn(Optional.of("sha256:new"));
        service.refresh();                      // the scheduler's scrape — taken before the operator pulled

        UpdateCheckOutcome outcome = service.checkForImageUpdates();

        assertThat(outcome.checked()).isTrue();
        assertThat(service.discover()).singleElement()
            .extracting(DockerService::updateAvailable).isEqualTo(UpdateAvailability.UP_TO_DATE);
    }

    @Test
    void checkForImageUpdates_agreesWithTheOperatorWhoJustPulled_evenIfTheRememberedAnswerIsStale() {
        // THE inversion, through the service. The remembered registry answer is X, fetched hours ago; upstream
        // has since moved to Y; the operator pulled and now runs Y. A check that accepted the remembered
        // answer would compare local Y against registry X, find a difference, and report UPDATE AVAILABLE on
        // the image they just updated — Vaier looking broken at the exact moment it is being audited.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:Y")));
        when(forResolvingRegistryDigest.resolveDigestNow(any())).thenReturn(Optional.of("sha256:Y"));

        service.checkForImageUpdates();

        assertThat(service.discover()).singleElement()
            .extracting(DockerService::updateAvailable).isEqualTo(UpdateAvailability.UP_TO_DATE);
        verify(forResolvingRegistryDigest, never()).resolveDigest(any());
    }

    @Test
    void checkForImageUpdates_reportsTheImageThatJustWentStale_soItIsMailedNowAndNotOnTheNextSweep() {
        // The operator clicked, the mark went yellow, and the mail came eight hours later from the daily
        // sweep — the same news, late. The check now folds its verdicts into the tracker exactly as the
        // sweep does and hands the edge to the driving side to mail with the mark.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forResolvingRegistryDigest.resolveDigestNow(any())).thenReturn(Optional.of("sha256:new"));

        UpdateCheckOutcome outcome = service.checkForImageUpdates();

        assertThat(outcome.newlyOutOfDate()).containsExactly(onVaierServer("vaultwarden/server:latest"));
    }

    @Test
    void checkForImageUpdates_reportsNoNewsForAnImageTheSweepAlreadyMailed() {
        // One verdict, one mail. The sweep latched it and mailed; a check the next morning must not mail
        // it again.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forResolvingRegistryDigest.resolveDigest(any())).thenReturn(Optional.of("sha256:new"));
        when(forResolvingRegistryDigest.resolveDigestNow(any())).thenReturn(Optional.of("sha256:new"));
        service.refresh();
        tracker.update(service.sweepImageUpdates());        // the watcher's evening: latched and mailed

        UpdateCheckOutcome outcome = service.checkForImageUpdates();

        assertThat(outcome.newlyOutOfDate()).isEmpty();
    }

    @Test
    void checkForImageUpdates_stampsTheSnapshotSoAnOpenExplorerReadsTheNewVerdict() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forResolvingRegistryDigest.resolveDigestNow(any())).thenReturn(Optional.of("sha256:new"));

        service.checkForImageUpdates();

        assertThat(service.discover()).singleElement()
            .extracting(DockerService::updateAvailable).isEqualTo(UpdateAvailability.UPDATE_AVAILABLE);
    }

    @Test
    void checkForImageUpdates_pushesTheResultOnTheTopicTheContainerPayloadsAlreadyRideSoTheExplorerRepaints() {
        // Clicking and seeing nothing change is the whole failure of this feature. The verdict moved, so every
        // open Explorer must be told to re-read — on the same topic/event DockerEventListener already uses for
        // a container changing state, because the shell listens to exactly that and the frontend never polls.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forResolvingRegistryDigest.resolveDigestNow(any())).thenReturn(Optional.of("sha256:new"));

        service.checkForImageUpdates();

        verify(forPublishingEvents).publish(eq("published-services"), eq("service-updated"), any());
    }

    @Test
    void sweepImageUpdates_pushesTheResultToo_soTheMarkArrivesWithTheMailAndNotOnTheNextReload() {
        // The path that mails the operator was the one that told open Explorers nothing: the alert landed
        // while the page it was about showed no mark until a manual reload.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forResolvingRegistryDigest.resolveDigest(any())).thenReturn(Optional.of("sha256:new"));
        service.refresh();

        service.sweepImageUpdates();

        verify(forPublishingEvents).publish(eq("published-services"), eq("service-updated"), any());
    }

    @Test
    void sweepImageUpdates_pushesNothingWhenNoVerdictMoved() {
        // Same rule as the operator's own check: only a change is worth waking every open Explorer.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forResolvingRegistryDigest.resolveDigest(any())).thenReturn(Optional.of("sha256:new"));
        service.refresh();
        service.sweepImageUpdates();                        // settles the verdict at UPDATE_AVAILABLE
        clearInvocations(forPublishingEvents);

        service.sweepImageUpdates();                        // same answer, second day

        verify(forPublishingEvents, never()).publish(any(), any(), any());
    }

    @Test
    void checkForImageUpdates_pushesNothingWhenNoVerdictMoved() {
        // The commonest outcome: they pulled, Vaier already agreed. Waking every open Explorer in the fleet to
        // redraw an identical page is noise, and the browser that clicked learns "nothing new" from its own
        // response rather than from an event.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:same")));
        when(forResolvingRegistryDigest.resolveDigestNow(any())).thenReturn(Optional.of("sha256:same"));
        service.checkForImageUpdates();                     // settles the verdict at UP_TO_DATE (a change)
        clearInvocations(forPublishingEvents);
        clock.advance(Duration.ofSeconds(90));              // past the floor, so this really checks again

        UpdateCheckOutcome second = service.checkForImageUpdates();

        assertThat(second.checked()).isTrue();
        assertThat(second.changed()).isFalse();
        verify(forPublishingEvents, never()).publish(any(), any(), any());
    }

    @Test
    void checkForImageUpdates_insideTheFloor_asksTheRegistriesNothingAndDoesNotClaimToHaveChecked() {
        // The rate-limit floor. A forced check bypasses every cache, so a click-spammed button is a direct
        // route to a 429 — which degrades every image to unknown and blinds the fleet at the worst moment.
        // Refuse honestly rather than re-issue: the operator is told when Vaier last really looked.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forResolvingRegistryDigest.resolveDigestNow(any())).thenReturn(Optional.of("sha256:new"));
        Instant firstCheckedAt = service.checkForImageUpdates().lastCheckedAt();

        clock.advance(Duration.ofSeconds(5));
        UpdateCheckOutcome second = service.checkForImageUpdates();

        assertThat(second.checked()).as("it did not check, and must not say it did").isFalse();
        assertThat(second.lastCheckedAt()).isEqualTo(firstCheckedAt);
        verify(forResolvingRegistryDigest, times(1)).resolveDigestNow(any());
    }

    @Test
    void checkForImageUpdates_insideTheFloor_doesNotReScrapeTheFleetEither() {
        // A refused check must cost nothing at all. Re-scraping every peer's Docker daemon over the VPN on a
        // rejected click would move the abuse from the registries onto the fleet.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forResolvingRegistryDigest.resolveDigestNow(any())).thenReturn(Optional.of("sha256:new"));
        service.checkForImageUpdates();

        service.checkForImageUpdates();

        verify(forGettingServerInfo, times(1)).getServicesWithExposedPorts(any());
    }

    @Test
    void checkForImageUpdates_clearsTheAlertStateOfAnImageItConfirmsUpToDate() {
        // The manual check must not permanently silence a future alert. Once the check has confirmed the pull,
        // the tracker must have forgotten the image — so if it goes stale again months later, the edge fires
        // and the operator IS mailed. (The tracker's own test proves the rule; this proves it is wired.)
        tracker.update(judged(onVaierServer("vaultwarden/server:latest")));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:same")));
        when(forResolvingRegistryDigest.resolveDigestNow(any())).thenReturn(Optional.of("sha256:same"));

        service.checkForImageUpdates();

        assertThat(tracker.update(judged(onVaierServer("vaultwarden/server:latest"))))
            .as("stale again after a confirmed pull — that is news again")
            .containsExactly(onVaierServer("vaultwarden/server:latest"));
    }

    @Test
    void checkForImageUpdates_latchesWhatItFindsStale_soTheSweepThatFollowsDoesNotMailItAgain() {
        // The check mails its own news now (newlyOutOfDate), so it must also latch it — or the evening's
        // sweep would find the same image unlatched and mail the operator the same thing twice.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forResolvingRegistryDigest.resolveDigestNow(any())).thenReturn(Optional.of("sha256:new"));

        service.checkForImageUpdates();

        assertThat(tracker.update(judged(onVaierServer("vaultwarden/server:latest"))))
            .as("already told by the check — the sweep has nothing to add")
            .isEmpty();
    }

    @Test
    void checkForImageUpdates_survivesADeadRegistryAndSaysItChecked() {
        // Total, like the daily sweep. A button that 500s because one registry is down would be a worse lie
        // than the stale mark it is clearing.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forResolvingRegistryDigest.resolveDigestNow(any()))
            .thenThrow(new RuntimeException("rate limited"));

        UpdateCheckOutcome outcome = service.checkForImageUpdates();

        assertThat(outcome.checked()).isTrue();
        assertThat(service.discover()).singleElement()
            .extracting(DockerService::updateAvailable).isEqualTo(UpdateAvailability.UNKNOWN);
    }

    @Test
    void sweepImageUpdates_leavesTheVerdictUnknownWhenTheRegistryCannotBeReached() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaultwarden", "vaultwarden/server:latest", "sha256:old")));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forResolvingRegistryDigest.resolveDigest(any()))
            .thenThrow(new RuntimeException("no egress"));
        service.refresh();

        assertThat(service.sweepImageUpdates().verdicts())
            .containsEntry(onVaierServer("vaultwarden/server:latest"), UpdateAvailability.UNKNOWN);
    }

    // --- Vaier-server container scrape + discover cache (local) ---

    @Test
    void scrapeVaierServerContainers_usesLocalDockerSocket() {
        List<DockerService> expected = List.of(dockerService("my-app", 8080));
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("unix:///var/run/docker.sock"))
        )).thenReturn(expected);

        assertThat(service.scrapeVaierServerContainers())
            .extracting(DockerService::containerName).containsExactly("my-app");
    }

    @Test
    void scrapeVaierServerContainers_judgesEachContainerAgainstVaiersOwnStack() {
        // The verdict has to reach the Explorer stamped on the container: whether an Update may be
        // offered is a business decision, and the browser decides nothing.
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("unix:///var/run/docker.sock"))
        )).thenReturn(List.of(composeManaged("traefik"), composeManaged("vaultwarden"),
            dockerService("hand-started", 9000)));

        assertThat(service.scrapeVaierServerContainers())
            .extracting(DockerService::containerName, DockerService::updateEligibility)
            .containsExactly(
                tuple("traefik", ContainerUpdateEligibility.VAIER_OWN_STACK),
                tuple("vaultwarden", ContainerUpdateEligibility.UPDATABLE),
                tuple("hand-started", ContainerUpdateEligibility.NOT_COMPOSE_MANAGED));
    }

    @Test
    void scrapeVaierServerContainers_returnsEmptyListWhenNoServicesRunning() {
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("unix:///var/run/docker.sock"))
        )).thenReturn(List.of());

        assertThat(service.scrapeVaierServerContainers()).isEmpty();
    }

    @Test
    void scrapeVaierServerContainers_propagatesExceptionFromAdapter() {
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("unix:///var/run/docker.sock"))
        )).thenThrow(new RuntimeException("socket not found"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> service.scrapeVaierServerContainers());
    }

    @Test
    void discover_beforeRefresh_returnsEmptySnapshot() {
        assertThat(service.discover()).isEmpty();
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void refresh_thenDiscover_servesTheScrapedVaierServerSnapshot() {
        List<DockerService> expected = List.of(dockerService("my-app", 8080));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(expected);

        service.refresh();

        assertThat(service.discover())
            .extracting(DockerService::containerName).containsExactly("my-app");
    }

    @Test
    void refresh_whenVaierScrapeFails_keepsServingThePreviousSnapshot() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenThrow(new RuntimeException("socket not found"));

        service.refresh();

        assertThat(service.discover()).isEmpty();
    }

    // --- getServicesWithExposedPorts ---

    @Test
    void getServicesWithExposedPorts_delegatesToPort() {
        Server server = new Server("10.13.13.2", 2375, false);
        DockerService dockerService = mock(DockerService.class);
        when(forGettingServerInfo.getServicesWithExposedPorts(server)).thenReturn(List.of(dockerService));

        assertThat(service.getServicesWithExposedPorts(server)).containsExactly(dockerService);
    }

    // --- discoverAll (peer containers) ---

    @Test
    void discoverAll_noClients_returnsEmpty() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of());

        assertThat(service.scrapePeerContainers()).isEmpty();
    }

    @Test
    void discoverAll_reachablePeer_returnsStatusOkWithContainers() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        List<DockerService> containers = List.of(dockerService("my-app", 8080));
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(containers);

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("OK");
        assertThat(result.get(0).peerId()).isEqualTo("alice");
        // The scrape says whose containers these are by identity. Without it the browser had to file them
        // under a display name, which meant crossing from an identity on every read and getting an empty
        // list for any machine whose two names disagreed by a character.
        assertThat(result.get(0).machineId()).isEqualTo(TestMachineIds.of("alice").value());
        assertThat(result.get(0).vpnIp()).isEqualTo("10.13.13.2");
        assertThat(result.get(0).containers())
            .extracting(DockerService::containerName).containsExactly("my-app");
    }

    @Test
    void discoverAll_judgesAPeersContainersAsTheOperatorsOwn() {
        // VaierServerCatalogue is about the Vaier server alone. A peer running its own traefik is the
        // operator's container, and theirs to update — the name says nothing about whose it is.
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class)))
            .thenReturn(List.of(composeManaged("traefik"), dockerService("hand-started", 9000)));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).containers())
            .extracting(DockerService::containerName, DockerService::updateEligibility)
            .containsExactly(
                tuple("traefik", ContainerUpdateEligibility.UPDATABLE),
                tuple("hand-started", ContainerUpdateEligibility.NOT_COMPOSE_MANAGED));
    }

    @Test
    void discoverAll_unreachablePeer_returnsStatusUnreachableWithEmptyContainers() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.3/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.3")).thenReturn("bob");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.3"))
            .thenReturn(Optional.of(peerConfig("bob", "10.13.13.3", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class)))
            .thenThrow(new RuntimeException("Connection refused"));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("UNREACHABLE");
        assertThat(result.get(0).peerId()).isEqualTo("bob");
        assertThat(result.get(0).containers()).isEmpty();
    }

    @Test
    void discoverAll_mixedPeers_handlesEachIndependently() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            client("10.13.13.2/32"),
            client("10.13.13.3/32")
        ));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.3")).thenReturn("bob");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.3"))
            .thenReturn(Optional.of(peerConfig("bob", "10.13.13.3", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(argThat(s -> s != null && "10.13.13.2".equals(s.getAddress()))))
            .thenReturn(List.of(dockerService("app", 8080)));
        when(forGettingServerInfo.getServicesWithExposedPorts(argThat(s -> s != null && "10.13.13.3".equals(s.getAddress()))))
            .thenThrow(new RuntimeException("timeout"));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PeerContainers::status).containsExactly("OK", "UNREACHABLE");
    }

    @Test
    void discoverAll_extractsIpFromCidrNotation() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.5/24")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.5")).thenReturn("charlie");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.5"))
            .thenReturn(Optional.of(peerConfig("charlie", "10.13.13.5", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).vpnIp()).isEqualTo("10.13.13.5");
    }

    @Test
    void discoverAll_mobileClient_isSkipped() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.10/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.10")).thenReturn("phone");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.10"))
            .thenReturn(Optional.of(peerConfig("phone", "10.13.13.10", MachineType.MOBILE_CLIENT)));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).isEmpty();
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_windowsClient_isSkipped() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.11/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.11")).thenReturn("laptop");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.11"))
            .thenReturn(Optional.of(peerConfig("laptop", "10.13.13.11", MachineType.WINDOWS_CLIENT)));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).isEmpty();
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_serverPeer_isQueried() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("server1");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("OK");
        verify(forGettingServerInfo).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_serverPeerWithStaleHandshake_skippedWithoutDockerQuery() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(disconnectedClient("10.13.13.5/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.5")).thenReturn("server1");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.5"))
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.5", MachineType.UBUNTU_SERVER)));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("UNREACHABLE");
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_unknownPeerConfig_defaultsToQueried() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.20/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.20")).thenReturn("unknown");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.20"))
            .thenReturn(Optional.empty());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("OK");
        verify(forGettingServerInfo).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_mixedTypes_onlyServerPeersQueried() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            client("10.13.13.2/32"),
            client("10.13.13.10/32"),
            client("10.13.13.3/32")
        ));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("server1");
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.10")).thenReturn("phone");
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.3")).thenReturn("server2");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.10"))
            .thenReturn(Optional.of(peerConfig("phone", "10.13.13.10", MachineType.MOBILE_CLIENT)));
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.3"))
            .thenReturn(Optional.of(peerConfig("server2", "10.13.13.3", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PeerContainers::peerId).containsExactly("server1", "server2");
    }

    @Test
    void discoverAll_peerWithHandshakeStalerThan180s_isSkippedAsDisconnected() {
        // Connectivity must follow the single domain rule VpnClient.isConnected() — a peer is
        // connected only while (now - handshake) < 180s. A 240s-stale handshake is disconnected.
        String handshake240sAgo = String.valueOf(System.currentTimeMillis() / 1000 - 240);
        VpnClient peer = new VpnClient("pubkey", "10.13.13.5/32", "1.2.3.4", "51820", handshake240sAgo, "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(peer));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.5")).thenReturn("server1");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.5"))
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.5", MachineType.UBUNTU_SERVER)));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("UNREACHABLE");
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_peerWithHandshakeWithin180s_isQueried() {
        String handshake120sAgo = String.valueOf(System.currentTimeMillis() / 1000 - 120);
        VpnClient peer = new VpnClient("pubkey", "10.13.13.5/32", "1.2.3.4", "51820", handshake120sAgo, "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(peer));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.5")).thenReturn("server1");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.5"))
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.5", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("OK");
    }

    @Test
    void discoverAll_peerWithMatchingWireguardImage_wireguardOutdatedIsFalse() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(wireguardContainer(WireguardClientImage.EXPECTED)));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).wireguardOutdated()).isFalse();
    }

    @Test
    void discoverAll_alwaysReportsExpectedWireguardImageOnReachablePeers() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(wireguardContainer("lscr.io/linuxserver/wireguard:1.0.20210914-ls42")));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).wireguardExpectedImage()).isEqualTo(WireguardClientImage.EXPECTED);
    }

    @Test
    void discoverAll_peerWithOlderWireguardImage_wireguardOutdatedIsTrue() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(wireguardContainer("lscr.io/linuxserver/wireguard:1.0.20210914-ls42")));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).wireguardOutdated()).isTrue();
    }

    @Test
    void discoverAll_peerWithLatestTagWireguard_wireguardOutdatedIsTrue() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(wireguardContainer("lscr.io/linuxserver/wireguard:latest")));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).wireguardOutdated()).isTrue();
    }

    @Test
    void discoverAll_peerWithNoWireguardContainer_wireguardOutdatedIsFalse() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(dockerService("app", 8080)));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).wireguardOutdated()).isFalse();
    }

    @Test
    void discoverAll_unreachablePeer_wireguardOutdatedIsFalse() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.3/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.3")).thenReturn("bob");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.3"))
            .thenReturn(Optional.of(peerConfig("bob", "10.13.13.3", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenThrow(new RuntimeException("Connection refused"));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).wireguardOutdated()).isFalse();
    }

    // --- refresh + discoverAll cache ---

    @Test
    void discoverAll_beforeRefresh_returnsEmptySnapshot() {
        assertThat(service.discoverAll()).isEmpty();
        verify(forGettingVpnClients, never()).getClients();
    }

    @Test
    void refresh_thenDiscoverAll_servesTheScrapedSnapshot() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.refresh();

        List<PeerContainers> result = service.discoverAll();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).peerId()).isEqualTo("alice");
    }

    @Test
    void discoverAll_servesCachedSnapshotWithoutRescraping() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.refresh();
        service.discoverAll();
        service.discoverAll();
        service.discoverAll();

        // wg is queried once per refresh, not once per read.
        verify(forGettingVpnClients, times(1)).getClients();
    }

    // --- getUnpublishedVaierServerServices ---

    @Test
    void getUnpublishedVaierServerServices_excludesWireguardContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class)))
            .thenReturn(List.of(localContainer(ServiceNames.WIREGUARD, 51820, "tcp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_excludesAutheliaContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer(ServiceNames.AUTHELIA, 9091, "tcp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_excludesRedisContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer(ServiceNames.REDIS, 6379, "tcp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_excludesVaierContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer(ServiceNames.VAIER, 8080, "tcp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_excludesWireguardMasqueradeContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer(ServiceNames.WIREGUARD_MASQUERADE, 8080, "tcp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_excludesTheDockerSocketProxy() {
        // docker-proxy serves the Docker API on 2375. It was offered for publishing for as long as the
        // catalogue went unrevised — one click from putting root on every container on a public hostname.
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer("docker-proxy", 2375, "tcp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_stillOffersTheOperatorsOwnContainers() {
        // The catalogue hides Vaier's stack, not the host. A container the operator runs alongside it is
        // exactly what the publishable list is for.
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer("pihole", 80, "tcp")));

        List<PublishableService> result = refreshThenGetUnpublished(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).containerName()).isEqualTo("pihole");
    }

    @Test
    void getUnpublishedVaierServerServices_traefikOnPort8080_includedWithDashboardRedirect() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer(ServiceNames.TRAEFIK, 8080, "tcp")));

        List<PublishableService> result = refreshThenGetUnpublished(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).containerName()).isEqualTo(ServiceNames.TRAEFIK);
        assertThat(result.get(0).port()).isEqualTo(8080);
        assertThat(result.get(0).rootRedirectPath()).isEqualTo("/dashboard/");
        assertThat(result.get(0).source()).isEqualTo(PublishableSource.VAIER_SERVER);
    }

    @Test
    void getUnpublishedVaierServerServices_traefikOnPort80_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer("traefik", 80, "tcp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_unknownContainerTcpPort_includedWithNullRedirectPath() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer("my-app", 3000, "tcp")));

        List<PublishableService> result = refreshThenGetUnpublished(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).containerName()).isEqualTo("my-app");
        assertThat(result.get(0).port()).isEqualTo(3000);
        assertThat(result.get(0).rootRedirectPath()).isNull();
    }

    @Test
    void getUnpublishedVaierServerServices_udpPort_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer("my-app", 3000, "udp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_alreadyPublishedRoute_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer("my-app", 3000, "tcp")));
        List<ReverseProxyRoute> existingRoutes = List.of(route("my-app", 3000));

        assertThat(refreshThenGetUnpublished(existingRoutes)).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_dockerThrows_returnsEmptyList() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenThrow(new RuntimeException("Docker socket unavailable"));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_containerOnVaierNetwork_usesContainerNameAndPrivatePort() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(new DockerService("id", "my-app", "image:latest", "latest",
                List.of(new PortMapping(3001, null, "tcp", "0.0.0.0")),
                List.of(VAIER_NETWORK), "running")));

        List<PublishableService> result = refreshThenGetUnpublished(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).address()).isEqualTo("my-app");
        assertThat(result.get(0).port()).isEqualTo(3001);
    }

    @Test
    void getUnpublishedVaierServerServices_containerOnOtherNetworkWithPublicPort_usesGatewayAndPublicPort() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(new DockerService("id", "uptime-kuma", "image:latest", "latest",
                List.of(new PortMapping(3001, 3001, "tcp", "0.0.0.0")),
                List.of("uptime-kuma_default"), "running")));

        List<PublishableService> result = refreshThenGetUnpublished(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).address()).isEqualTo(GATEWAY_IP);
        assertThat(result.get(0).port()).isEqualTo(3001);
        assertThat(result.get(0).containerName()).isEqualTo("uptime-kuma");
    }

    @Test
    void getUnpublishedVaierServerServices_alreadyPublishedUnderItsOtherSpelling_excluded() {
        // A container on Vaier's network that also publishes a host port can be addressed two ways. Routes
        // published before Vaier could see it on its own network hold the gateway spelling — and offering
        // it again as a candidate would tell the operator something plainly untrue about their own stack.
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(new DockerService("id", "pihole", "image:latest", "latest",
                List.of(new PortMapping(80, 8053, "tcp", "0.0.0.0")),
                List.of("vaier_vaier-network"), "running")));

        assertThat(refreshThenGetUnpublished(List.of(route(GATEWAY_IP, 8053)))).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_containerOnOtherNetworkWithoutPublicPort_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(new DockerService("id", "my-app", "image:latest", "latest",
                List.of(new PortMapping(3001, null, "tcp", "0.0.0.0")),
                List.of("some-other-network"), "running")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_crossNetworkContainerAlreadyPublished_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(new DockerService("id", "uptime-kuma", "image:latest", "latest",
                List.of(new PortMapping(3001, 3001, "tcp", "0.0.0.0")),
                List.of("uptime-kuma_default"), "running")));
        List<ReverseProxyRoute> existingRoutes = List.of(route(GATEWAY_IP, 3001));

        assertThat(refreshThenGetUnpublished(existingRoutes)).isEmpty();
    }

    // --- helpers ---

    private VpnClient client(String allowedIps) {
        String recentHandshake = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        return new VpnClient("pubkey", allowedIps, "1.2.3.4", "51820", recentHandshake, "0", "0");
    }

    private VpnClient disconnectedClient(String allowedIps) {
        return new VpnClient("pubkey", allowedIps, "1.2.3.4", "51820", "0", "0", "0");
    }

    private PeerConfiguration peerConfig(String name, String ip, MachineType type) {
        // A stable identity per name, so a test can stub the scrape and assert on the id it files under.
        return new PeerConfiguration(name, PeerId.display(name), ip, "", type, null, null, null, null,
            null, TestMachineIds.of(name), null);
    }

    // --- discoverAllLanServerContainers (#177, #184) ---

    @Test
    void discoverAllLanServerContainers_emptyWhenNoServersRegistered() {
        when(forGettingLanServers.getAll()).thenReturn(List.of());

        assertThat(service.discoverAllLanServerContainers()).isEmpty();
    }

    @Test
    void discoverAllLanServerContainers_relayResolved_scrapesDockerSocket() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5")
        ));
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("tcp://192.168.3.50:2375"))
        )).thenReturn(List.of(dockerService("plex", 32400)));

        var results = service.discoverAllLanServerContainers();

        assertThat(results).hasSize(1);
        var hostContainers = results.get(0);
        assertThat(hostContainers.name()).isEqualTo("nas");
        assertThat(hostContainers.lanAddress()).isEqualTo("192.168.3.50");
        assertThat(hostContainers.dockerPort()).isEqualTo(2375);
        assertThat(hostContainers.relayPeerName()).isEqualTo("apalveien5");
        assertThat(hostContainers.status()).isEqualTo("OK");
        assertThat(hostContainers.containers()).hasSize(1);
    }

    @Test
    void discoverAllLanServerContainers_serverAnchored_scrapesDirectly() {
        // A LAN server in the Vaier server's own subnet is anchored at "Vaier server" — the
        // scrape connects straight from the Vaier container, no relay hop.
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("vpc-box", "172.31.5.20", true, 2375), "Vaier server")
        ));
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("tcp://172.31.5.20:2375"))
        )).thenReturn(List.of(dockerService("plex", 32400)));

        var results = service.discoverAllLanServerContainers();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("vpc-box");
        assertThat(results.get(0).relayPeerName()).isEqualTo("Vaier server");
        assertThat(results.get(0).status()).isEqualTo("OK");
        assertThat(results.get(0).containers()).hasSize(1);
    }

    @Test
    void discoverAllLanServerContainers_relayUnknown_marksUnreachableAndDoesNotScrape() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), null)
        ));

        var results = service.discoverAllLanServerContainers();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("UNREACHABLE");
        assertThat(results.get(0).containers()).isEmpty();
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAllLanServerContainers_dockerScrapeFails_marksUnreachable() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5")
        ));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenThrow(new RuntimeException("connection refused"));

        var results = service.discoverAllLanServerContainers();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("UNREACHABLE");
        assertThat(results.get(0).relayPeerName()).isEqualTo("apalveien5");
    }

    @Test
    void discoverAllLanServerContainers_skipsRunsDockerFalse() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "apalveien5"),
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5")
        ));
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("tcp://192.168.3.50:2375"))
        )).thenReturn(List.of(dockerService("plex", 32400)));

        var results = service.discoverAllLanServerContainers();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("nas");
    }

    @Test
    void discoverLanServerContainersForHost_runsDockerFalse_throws() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "apalveien5")
        ));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.discoverLanServerContainersForHost("printer"));
    }

    @Test
    void discoverLanServerContainersForHost_unknownName_throws() {
        when(forGettingLanServers.getAll()).thenReturn(List.of());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.discoverLanServerContainersForHost("ghost"));
    }

    @Test
    void discoverLanServerContainersForHost_runsDockerTrue_returnsContainers() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5")
        ));
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("tcp://192.168.3.50:2375"))
        )).thenReturn(List.of(dockerService("plex", 32400)));

        var result = service.discoverLanServerContainersForHost("nas");

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.containers()).hasSize(1);
    }

    // --- Vaier's own stack is not swept at all (#353) ---

    @Test
    void vaiersOwnStackIsNeverAskedAboutAtTheRegistry_norGivenAVerdict() {
        // Not swept-and-hidden: the request itself is what spends the rate limit, and it is shared with
        // every image the operator CAN act on.
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of(
            imaged("traefik", "traefik:v3.6.14", "sha256:local"),
            imaged("pihole", "pihole/pihole:latest", "sha256:local")));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forResolvingRegistryDigest.resolveDigest(any())).thenReturn(Optional.of("sha256:newer"));
        service.refresh();

        assertThat(service.sweepImageUpdates().verdicts())
            .containsOnlyKeys(onVaierServer("pihole/pihole:latest"));
        verify(forResolvingRegistryDigest, never())
            .resolveDigest(argThat(reference -> reference.toString().contains("traefik")));
    }

    @Test
    void vaierItselfIsNotSweptEither_soNoMarkCanTalkAnOperatorIntoADowngrade() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("vaier", "getvaier/vaier:latest", "sha256:localbuild")));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        service.refresh();

        assertThat(service.sweepImageUpdates().verdicts()).isEmpty();
        verify(forResolvingRegistryDigest, never()).resolveDigest(any());
    }

    @Test
    void aMarkAlreadyHeldForVaiersOwnStackGoesClearOnTheVeryNextSweep() {
        // The operator's actual complaint. Stopping the sweep is not enough on its own: the verdict from
        // before the change sits in memory and would keep the dot lit until a restart. The sweep's own
        // write is authoritative — it replaces the map wholesale — so the stale entry goes with it.
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(imaged("traefik", "traefik:v3.6.14", "sha256:local")));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        // A registry that really is serving something newer, so a sweep that still asked about traefik
        // would write the mark straight back — this test would pass for the wrong reason without it.
        lenient().when(forResolvingRegistryDigest.resolveDigest(any())).thenReturn(Optional.of("sha256:newer"));
        service.refresh();
        snapshotStore.storeImageUpdateVerdicts(Map.of(
            onVaierServer("traefik:v3.6.14"), UpdateAvailability.UPDATE_AVAILABLE));
        assertThat(service.discover()).singleElement()
            .extracting(DockerService::updateAvailable).isEqualTo(UpdateAvailability.UPDATE_AVAILABLE);

        service.sweepImageUpdates();

        assertThat(service.discover()).singleElement()
            .extracting(DockerService::updateAvailable).isEqualTo(UpdateAvailability.UNKNOWN);
    }

    @Test
    void aPeersOwnTraefikIsStillSwept_becauseItIsTheOperatorsContainer() {
        // The whole reason the two judging entry points are separate. Silencing Vaier's own stack must not
        // silence a peer that happens to run a container by the same name.
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class)))
            .thenReturn(List.of(imaged("traefik", "traefik:v3.6.14", "sha256:local")));
        when(forResolvingRegistryDigest.resolveDigest(any())).thenReturn(Optional.of("sha256:newer"));
        service.refresh();

        assertThat(service.sweepImageUpdates().verdicts()).containsEntry(
            new ScopedImage(TestMachineIds.of("alice").value(), "traefik:v3.6.14"),
            UpdateAvailability.UPDATE_AVAILABLE);
    }

    // --- Updating a container's image (#352) ---

    private static final MachineId ALICE = TestMachineIds.of("alice");
    private static final SshTarget ALICE_TARGET = new SshTarget("10.13.13.2", 22, "ubuntu",
        AuthMethod.PASSWORD, "pw", null, "SHA256:pinned", ALICE);

    /** Scrape a peer called alice carrying {@code containers}, so the fleet has something to update. */
    private void fleetWithPeerContainers(List<DockerService> containers) {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(containers);
        service.refresh();
    }

    private static CommandResult succeeded() {
        return new CommandResult(0, "", "", false, "SHA256:pinned");
    }

    @Test
    void update_acceptsAndReturnsAtOnce_soNoRequestThreadEverWaitsOnAPull() {
        fleetWithPeerContainers(List.of(composeManaged("vaultwarden")));
        when(forResolvingSshTargets.resolve(ALICE)).thenReturn(ALICE_TARGET);

        service.updateContainerImage(ALICE, "vaultwarden");

        // Nothing has been run yet: the slow work is queued, not done on the caller's thread.
        verifyNoInteractions(forRunningSshCommands);
        assertThat(updateExecutor.pending()).isEqualTo(1);
    }

    @Test
    void theQueuedUpdate_pullsThenRecreates_andPushesTheSettledOutcome() {
        fleetWithPeerContainers(List.of(composeManaged("vaultwarden")));
        when(forResolvingSshTargets.resolve(ALICE)).thenReturn(ALICE_TARGET);
        when(forRunningSshCommands.run(any(), anyString(), any())).thenReturn(succeeded());

        service.updateContainerImage(ALICE, "vaultwarden");
        updateExecutor.runPending();

        verify(forRunningSshCommands).run(eq(ALICE_TARGET),
            argThat(c -> c.startsWith("docker compose") && c.endsWith("pull 'vaultwarden'")),
            eq(ContainerUpdate.PULL_TIMEOUT));
        verify(forRunningSshCommands).run(eq(ALICE_TARGET),
            argThat(c -> c.endsWith("up -d 'vaultwarden'")), eq(ContainerUpdate.RECREATE_TIMEOUT));
        verify(forPublishingEvents).publish(eq("vpn-peers"), eq("container-update-settled"),
            argThat(data -> data.contains("\"outcome\":\"UPDATED\"")
                && data.contains(ALICE.value()) && data.contains("vaultwarden")));
    }

    @Test
    void update_ofAContainerThatMachineDoesNotHave_isNotFound_andQueuesNothing() {
        fleetWithPeerContainers(List.of(composeManaged("vaultwarden")));

        assertThatThrownBy(() -> service.updateContainerImage(ALICE, "paperless"))
            .isInstanceOf(NotFoundException.class);

        assertThat(updateExecutor.pending()).isZero();
        verifyNoInteractions(forResolvingSshTargets);
    }

    @Test
    void update_ofAContainerVaierCannotRecreate_isRefusedWithTheReason() {
        fleetWithPeerContainers(List.of(dockerService("hand-started", 9000)));

        assertThatThrownBy(() -> service.updateContainerImage(ALICE, "hand-started"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("how it was started");

        assertThat(updateExecutor.pending()).isZero();
    }

    @Test
    void update_ofVaiersOwnStackOnTheVaierServer_isRefused() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class)))
            .thenReturn(List.of(composeManaged("traefik")));
        service.refresh();

        assertThatThrownBy(() ->
            service.updateContainerImage(TestMachineIds.of("Vaier server"), "traefik"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Vaier release");
    }

    @Test
    void update_ofTheVaierServersOwnOperatorContainer_isAccepted() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class)))
            .thenReturn(List.of(composeManaged("vaultwarden")));
        service.refresh();
        MachineId vaierServer = TestMachineIds.of("Vaier server");
        when(forResolvingSshTargets.resolve(vaierServer)).thenReturn(ALICE_TARGET);

        service.updateContainerImage(vaierServer, "vaultwarden");

        assertThat(updateExecutor.pending()).isEqualTo(1);
    }

    @Test
    void update_ofALanServersContainer_readsTheCachedLanScrape() {
        MachineId nas = TestMachineIds.of("nas");
        when(forGettingLanServerScrape.getLanServerContainers()).thenReturn(List.of(
            new LanServerContainers(nas.value(), "nas", "192.168.3.50", 2375, "colina27", "OK",
                ContainerUpdateEligibility.judgeOperatorContainers(List.of(composeManaged("plex")), DockerCommandAccess.GRANTED))));
        when(forResolvingSshTargets.resolve(nas)).thenReturn(ALICE_TARGET);

        service.updateContainerImage(nas, "plex");

        assertThat(updateExecutor.pending()).isEqualTo(1);
    }

    @Test
    void update_ofAMachineWithNoStoredCredential_failsBeforeAnythingIsQueued() {
        fleetWithPeerContainers(List.of(composeManaged("vaultwarden")));
        when(forResolvingSshTargets.resolve(ALICE)).thenThrow(new NoHostCredentialException("alice"));

        assertThatThrownBy(() -> service.updateContainerImage(ALICE, "vaultwarden"))
            .isInstanceOf(NoHostCredentialException.class);

        assertThat(updateExecutor.pending()).isZero();
    }

    /** A compose-managed container carrying a local digest, so a sweep has two digests to compare. */
    private static DockerService composeManagedImaged(String name, String localDigest) {
        return composeManaged(name).toBuilder().imageDigest(localDigest).build();
    }

    /** That peer's container as the Explorer reads it — verdict and all. */
    private DockerService peerContainerNamed(String name) {
        return service.discoverAll().stream()
            .flatMap(peer -> peer.containers().stream())
            .filter(c -> c.containerName().equals(name))
            .findFirst().orElseThrow();
    }

    /** Sweep the fleet with a registry that serves something newer, so the container is marked. */
    private void markedOutOfDate(String containerName) {
        fleetWithPeerContainers(List.of(composeManagedImaged(containerName, "sha256:old")));
        when(forResolvingRegistryDigest.resolveDigest(any())).thenReturn(Optional.of("sha256:new"));
        service.sweepImageUpdates();
        assertThat(peerContainerNamed(containerName).updateAvailable())
            .isEqualTo(UpdateAvailability.UPDATE_AVAILABLE);
    }

    @Test
    void anUpdatedContainerStopsBeingMarkedOutOfDate_withoutWaitingForTheNextSweep() {
        // The live bug: the sweep remembers its verdict per (machine, image TAG), and an update changes
        // the digest and not the tag — so re-scraping re-applied the same remembered UPDATE_AVAILABLE and
        // the yellow mark outlived the update that resolved it, indefinitely.
        markedOutOfDate("vaultwarden");
        when(forResolvingSshTargets.resolve(ALICE)).thenReturn(ALICE_TARGET);
        when(forRunningSshCommands.run(any(), anyString(), any())).thenReturn(succeeded());

        service.updateContainerImage(ALICE, "vaultwarden");
        updateExecutor.runPending();

        // Forgotten, not stamped up to date: Vaier pulled and recreated, it never re-compared the digests.
        assertThat(peerContainerNamed("vaultwarden").updateAvailable())
            .isEqualTo(UpdateAvailability.UNKNOWN);
    }

    @Test
    void anUpdateThatFailed_leavesTheMarkStanding_becauseTheOldImageIsStillWhatRuns() {
        markedOutOfDate("vaultwarden");
        when(forResolvingSshTargets.resolve(ALICE)).thenReturn(ALICE_TARGET);
        when(forRunningSshCommands.run(any(), anyString(), any()))
            .thenReturn(new CommandResult(1, "", "manifest unknown", false, "SHA256:pinned"));

        service.updateContainerImage(ALICE, "vaultwarden");
        updateExecutor.runPending();

        assertThat(peerContainerNamed("vaultwarden").updateAvailable())
            .isEqualTo(UpdateAvailability.UPDATE_AVAILABLE);
    }

    @Test
    void theMarkIsAlreadyGoneWhenTheSettledEventFires_becauseTheBrowserReReadsOnIt() {
        // Ordering, not bookkeeping: the Explorer re-reads its containers when the settled event arrives.
        // Announcing first would hand it the stale mark and leave it there until something else moved.
        markedOutOfDate("vaultwarden");
        when(forResolvingSshTargets.resolve(ALICE)).thenReturn(ALICE_TARGET);
        when(forRunningSshCommands.run(any(), anyString(), any())).thenReturn(succeeded());
        AtomicReference<UpdateAvailability> asTheBrowserWouldSeeIt = new AtomicReference<>();
        doAnswer(invocation -> {
            asTheBrowserWouldSeeIt.set(peerContainerNamed("vaultwarden").updateAvailable());
            return null;
        }).when(forPublishingEvents).publish(any(), eq("container-update-settled"), any());

        service.updateContainerImage(ALICE, "vaultwarden");
        updateExecutor.runPending();

        assertThat(asTheBrowserWouldSeeIt.get()).isEqualTo(UpdateAvailability.UNKNOWN);
    }

    /**
     * Run {@code work} with ContainerService's log captured.
     *
     * <p>Sets the level too, and restores it: logback's context is JVM-wide, so once the one
     * {@code @ActiveProfiles("integration")} test has booted Spring, {@code net.vaier} sits at WARN for
     * every test after it and an INFO capture silently returns nothing. Which tests those are depends on
     * surefire's ordering, which differs between a laptop and CI.
     */
    private List<ILoggingEvent> whileCapturingTheLog(Runnable work) {
        Logger serviceLog = (Logger) LoggerFactory.getLogger(ContainerService.class);
        Level original = serviceLog.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLog.addAppender(appender);
        serviceLog.setLevel(Level.INFO);
        try {
            work.run();
            return List.copyOf(appender.list);
        } finally {
            serviceLog.detachAppender(appender);
            serviceLog.setLevel(original);
        }
    }

    @Test
    void everySettledUpdateIsLogged_becauseRecreatingSomeonesContainerIsWorthAnAuditTrail() {
        // Nothing about an update reached the log at all, so there was no way to tell from a host's
        // Vaier logs whether an update had even been attempted.
        fleetWithPeerContainers(List.of(composeManaged("vaultwarden")));
        when(forResolvingSshTargets.resolve(ALICE)).thenReturn(ALICE_TARGET);
        when(forRunningSshCommands.run(any(), anyString(), any())).thenReturn(succeeded());

        List<ILoggingEvent> logged = whileCapturingTheLog(() -> {
            service.updateContainerImage(ALICE, "vaultwarden");
            updateExecutor.runPending();
        });

        assertThat(logged).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                .contains("vaultwarden")
                .contains(ALICE.value())
                .contains("UPDATED");
        });
    }

    @Test
    void aFailedUpdatesReasonReachesTheLog_soTheOperatorCanBeToldWhy() {
        // The live complaint: PULL_FAILED in the browser and absolutely nothing in `docker logs vaier`.
        fleetWithPeerContainers(List.of(composeManaged("netdata")));
        when(forResolvingSshTargets.resolve(ALICE)).thenReturn(ALICE_TARGET);
        when(forRunningSshCommands.run(any(), anyString(), any())).thenReturn(new CommandResult(1, "",
            "Error response from daemon: pull access denied for netdata", false, "SHA256:pinned"));

        List<ILoggingEvent> logged = whileCapturingTheLog(() -> {
            service.updateContainerImage(ALICE, "netdata");
            updateExecutor.runPending();
        });

        assertThat(logged).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                .contains("netdata")
                .contains("PULL_FAILED")
                .contains("pull access denied");
        });
    }

    @Test
    void theSettledEventTellsTheOperatorWhy_notOnlyThatItFailed() {
        fleetWithPeerContainers(List.of(composeManaged("netdata")));
        when(forResolvingSshTargets.resolve(ALICE)).thenReturn(ALICE_TARGET);
        when(forRunningSshCommands.run(any(), anyString(), any())).thenReturn(new CommandResult(1, "",
            "Error response from daemon: pull access denied for netdata", false, "SHA256:pinned"));

        service.updateContainerImage(ALICE, "netdata");
        updateExecutor.runPending();

        verify(forPublishingEvents).publish(eq("vpn-peers"), eq("container-update-settled"),
            argThat(data -> data.contains("pull access denied for netdata")
                && data.contains("still running")));
    }

    @Test
    void anUpdateThatCouldNotBeCarriedOut_isAnnouncedAnyway_soTheExplorerNeverWaitsForever() {
        // The hardest case for the async boundary: a failure nobody anticipated, on the far side of the
        // hand-off, where nothing is left to turn it into a 4xx. The domain rules what it means; what is
        // proven here is the service's own half — that a settled outcome is always pushed.
        fleetWithPeerContainers(List.of(composeManaged("vaultwarden")));
        when(forResolvingSshTargets.resolve(ALICE)).thenReturn(ALICE_TARGET);
        when(forRunningSshCommands.run(any(), anyString(), any()))
            .thenThrow(new IllegalStateException("a bug nobody saw coming"));

        service.updateContainerImage(ALICE, "vaultwarden");
        updateExecutor.runPending();

        verify(forPublishingEvents).publish(eq("vpn-peers"), eq("container-update-settled"),
            argThat(data -> data.contains("\"outcome\":\"UNREACHABLE\"")));
    }

    private DockerService dockerService(String name, int port) {
        return new DockerService("id123", name, "image:latest", "latest",
            List.of(new PortMapping(port, port, "tcp", "0.0.0.0")), List.of(), "running");
    }

    /** A compose-managed container, as every scrape of a real fleet host reports one. */
    private static DockerService composeManaged(String name) {
        return DockerService.builder()
            .containerId("id-" + name)
            .containerName(name)
            .image(name + ":latest")
            .version("latest")
            .ports(List.of(new PortMapping(80, 8080, "tcp", "0.0.0.0")))
            .networks(List.of())
            .state("running")
            .composeCoordinates(ComposeCoordinates.fromLabels(Map.of(
                "com.docker.compose.project", name,
                "com.docker.compose.service", name,
                "com.docker.compose.project.config_files", "/srv/" + name + "/docker-compose.yml",
                "com.docker.compose.project.working_dir", "/srv/" + name)).orElseThrow())
            .build();
    }

    private DockerService wireguardContainer(String image) {
        return new DockerService("wg-id", "wireguard-client", image, "",
            List.of(), List.of(), "running");
    }

    private DockerService localContainer(String name, int port, String type) {
        return new DockerService("id", name, "image:latest", "latest",
            List.of(new PortMapping(port, null, type, "0.0.0.0")),
            List.of(VAIER_NETWORK), "running");
    }

    private ReverseProxyRoute route(String address, int port) {
        return new ReverseProxyRoute("route", "app.example.com", address, port, "svc", null);
    }

    /**
     * getUnpublishedVaierServerServices reads ContainerService's cached snapshot, so a test
     * must refresh() first to populate it from the stubbed Vaier-server Docker scrape.
     */
    private List<PublishableService> refreshThenGetUnpublished(List<ReverseProxyRoute> routes) {
        service.refresh();
        return service.getUnpublishedVaierServerServices(routes);
    }

    // --- container standings: what was running and is not any more (#356) ---

    private static DockerService named(String name, String state) {
        return new DockerService("id-" + name, name, "ghcr.io/" + name + ":latest", "v",
            List.of(), List.of(VAIER_NETWORK), state);
    }

    /** A connected server peer whose Docker daemon answers with exactly these containers. */
    private void peerRunning(DockerService... containers) {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.5/32")));
        lenient().when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.5")).thenReturn("apalveien5");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.5"))
            .thenReturn(Optional.of(peerConfig("apalveien5", "10.13.13.5", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(
                argThat(server -> server != null && "10.13.13.5".equals(server.getAddress()))))
            .thenReturn(List.of(containers));
        // Vaier's own host answers with nothing, which is read as a scrape that did not answer rather
        // than as a host with no containers — the rule that keeps a failed local scrape quiet.
        lenient().when(forGettingServerInfo.getServicesWithExposedPorts(
                argThat(server -> server == null || !"10.13.13.5".equals(server.getAddress()))))
            .thenReturn(List.of());
    }

    /** One round of the scheduler's tick: scrape, then judge what the scrape found. */
    private List<ContainerStandingTracker.Verdict> scrapeAndJudge() {
        service.refresh();
        return service.judgeContainerStandings();
    }

    @Test
    void judgeContainerStandings_isSilentWhileEverythingThatWasRunningStillIs() {
        peerRunning(named("webtrees", "running"));

        assertThat(scrapeAndJudge()).isEmpty();
        assertThat(scrapeAndJudge()).isEmpty();
        verify(forPublishingEvents, never()).publish(eq("vpn-peers"), anyString(), any());
    }

    @Test
    void judgeContainerStandings_alertsWhenAContainerOnAPeerStops_andWakesTheExplorer() {
        peerRunning(named("webtrees", "running"));
        scrapeAndJudge();

        // The machine rebooted; the container with `restart: no` did not come back with it.
        peerRunning(named("webtrees", "exited"));
        assertThat(scrapeAndJudge()).isEmpty();
        List<ContainerStandingTracker.Verdict> verdicts = scrapeAndJudge();

        assertThat(verdicts).singleElement().satisfies(verdict -> {
            assertThat(verdict.outcome()).isEqualTo(ContainerStandingTracker.Outcome.ALERT);
            assertThat(verdict.standing().containerName()).isEqualTo("webtrees");
            assertThat(verdict.standing().machineId()).isEqualTo(TestMachineIds.of("apalveien5"));
        });
        verify(forPublishingEvents).publish("vpn-peers", "container-standing-changed", "");
    }

    @Test
    void judgeContainerStandings_aPeerThatDidNotAnswerMovesNothing() {
        peerRunning(named("webtrees", "running"));
        scrapeAndJudge();

        // The peer goes quiet: the scrape reports UNREACHABLE and no containers at all.
        when(forGettingVpnClients.getClients()).thenReturn(List.of(disconnectedClient("10.13.13.5/32")));
        assertThat(scrapeAndJudge()).isEmpty();
        assertThat(scrapeAndJudge()).isEmpty();

        assertThat(containerStandings.standingsFor(TestMachineIds.of("apalveien5"))).singleElement()
            .satisfies(standing -> assertThat(standing.standing()).isEqualTo(ContainerStanding.RUNNING));
    }

    @Test
    void judgeContainerStandings_watchesVaiersOwnStackToo() {
        // The masquerade sidecar dying after a deploy is exactly this bug on Vaier's own host.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(named("wireguard-masquerade", "running")));
        scrapeAndJudge();

        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(named("wireguard-masquerade", "exited")));
        scrapeAndJudge();

        assertThat(scrapeAndJudge()).singleElement().satisfies(verdict -> {
            assertThat(verdict.outcome()).isEqualTo(ContainerStandingTracker.Outcome.ALERT);
            assertThat(verdict.standing().machineId()).isEqualTo(TestMachineIds.of("Vaier server"));
        });
    }

    @Test
    void judgeContainerStandings_aStoppedContainerStaysInTheScrape_soItCanRecover() {
        // The live gap this pins: a stopped container keeps its published bindings and so stays in the
        // scrape, present-but-not-running. Before that, every exited container vanished from the scrape,
        // read as REMOVED, was forgotten after its one alert — and starting it again said nothing at all.
        peerRunning(named("busybox-probe", "running"));
        scrapeAndJudge();

        peerRunning(named("busybox-probe", "exited"));
        scrapeAndJudge();
        assertThat(scrapeAndJudge()).singleElement().satisfies(verdict ->
            assertThat(verdict.outcome()).isEqualTo(ContainerStandingTracker.Outcome.ALERT));

        // The standing is KEPT, because the container is still there — so the machine's card goes on
        // saying so, and there is something for a recovery to cancel.
        assertThat(containerStandings.standingsFor(TestMachineIds.of("apalveien5"))).singleElement()
            .satisfies(standing -> assertThat(standing.standing()).isEqualTo(ContainerStanding.GONE));

        peerRunning(named("busybox-probe", "running"));
        assertThat(scrapeAndJudge()).singleElement().satisfies(verdict ->
            assertThat(verdict.outcome()).isEqualTo(ContainerStandingTracker.Outcome.RECOVERED));
        assertThat(scrapeAndJudge()).isEmpty();
    }

    @Test
    void getContainerStandings_isEveryStandingVaierHolds() {
        peerRunning(named("webtrees", "running"));
        scrapeAndJudge();

        assertThat(service.getContainerStandings())
            .extracting(MachineContainerStanding::containerName)
            .containsExactly("webtrees");
    }
}
