package net.vaier.application.service;

import net.vaier.application.GetDiscoveredLanMachinesUseCase.LanScanSnapshot;
import net.vaier.application.GetDiscoveredLanMachinesUseCase.ScanStatus;
import net.vaier.application.ListScannableLansUseCase.ScannableLan;
import net.vaier.domain.DiscoveredLanMachine;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.MachineType;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForManagingIgnoredLanMachines;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import net.vaier.domain.port.ForScanningLan;
import net.vaier.domain.port.ForScanningLan.ScannedHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class LanScannerServiceTest {

    // A recording event publisher so we can assert the completion notification fires.
    private final List<String> events = new ArrayList<>();
    private final ForPublishingEvents recordingEvents = (topic, name, data) -> events.add(name);

    // An in-memory ignore-list the tests can drive through the service's use cases.
    private final Set<String> ignoredKeys = new LinkedHashSet<>();
    private final ForManagingIgnoredLanMachines ignoreStore = new ForManagingIgnoredLanMachines() {
        public Set<String> getIgnoredKeys() { return ignoredKeys; }
        public void ignore(String key) { ignoredKeys.add(key); }
        public void unignore(String key) { ignoredKeys.remove(key); }
    };

    // The CIDRs the scanner was asked to sweep — so a targeted scan can be shown to touch only one LAN.
    private final List<String> sweptCidrs = new ArrayList<>();

    private PeerConfiguration relay(String id, String lanCidr) {
        return new PeerConfiguration(id, id, "10.13.13.5", "", MachineType.UBUNTU_SERVER, lanCidr, null, null);
    }

    private LanScannerService service(List<PeerConfiguration> peers, Map<String, List<ScannedHost>> scans,
                                      Optional<String> serverCidr, List<LanServer> registered,
                                      Executor executor) {
        ForScanningLan scanner = cidr -> { sweptCidrs.add(cidr); return scans.getOrDefault(cidr, List.of()); };
        ForGettingPeerConfigurations peerConfigs = new ForGettingPeerConfigurations() {
            public Optional<PeerConfiguration> getPeerConfigByName(String n) { return Optional.empty(); }
            public Optional<PeerConfiguration> getPeerConfigByIp(String ip) { return Optional.empty(); }
            public List<PeerConfiguration> getAllPeerConfigs() { return peers; }
        };
        ForResolvingServerLanCidr serverLanCidr = () -> serverCidr;
        ForGettingLanServers lanServers = () -> registered.stream()
            .map(s -> new LanServerView(s, "x")).toList();
        // The snapshot store is real infrastructure (an in-memory store adapter) so the scan
        // results flow through it exactly as they do in production; nothing to stub.
        return new LanScannerService(scanner, peerConfigs, serverLanCidr, lanServers, ignoreStore,
            recordingEvents, new net.vaier.adapter.driven.InMemoryDiscoveredLanMachineStore(), executor);
    }

    /** Runs the scan task inline so the test stays deterministic. */
    private static final Executor INLINE = Runnable::run;

    @Test
    void snapshotBeforeAnyScanIsIdleAndEmpty() {
        LanScannerService service = service(List.of(), Map.of(), Optional.empty(), List.of(), INLINE);

        LanScanSnapshot snapshot = service.snapshot();
        assertThat(snapshot.status()).isEqualTo(ScanStatus.IDLE);
        assertThat(snapshot.machines()).isEmpty();
        assertThat(snapshot.lastScanCompleted()).isNull();
    }

    @Test
    void startScanPopulatesTheSnapshotAndReturnsToIdle() {
        LanScannerService service = service(
            List.of(relay("apalveien5", "192.168.3.0/24")),
            Map.of("192.168.3.0/24", List.of(new ScannedHost("192.168.3.10", List.of(2375), "docker01"))),
            Optional.empty(), List.of(), INLINE);

        service.startScan();

        LanScanSnapshot snapshot = service.snapshot();
        assertThat(snapshot.status()).isEqualTo(ScanStatus.IDLE);
        assertThat(snapshot.lastScanCompleted()).isNotNull();
        assertThat(snapshot.machines())
            .extracting(DiscoveredLanMachine::ipAddress, DiscoveredLanMachine::relayAnchor)
            .containsExactly(tuple("192.168.3.10", "apalveien5"));
    }

    @Test
    void startScanScansTheServerLanCidrTaggedWithTheVaierServer() {
        LanScannerService service = service(
            List.of(),
            Map.of("172.31.0.0/24", List.of(new ScannedHost("172.31.0.9", List.of(80), null))),
            Optional.of("172.31.0.0/24"), List.of(), INLINE);

        service.startScan();

        assertThat(service.snapshot().machines())
            .extracting(DiscoveredLanMachine::ipAddress, DiscoveredLanMachine::relayAnchor)
            .containsExactly(tuple("172.31.0.9", LanAnchor.VAIER_SERVER_NAME));
    }

    @Test
    void startScanSkipsRelayPeersWithoutALanCidr() {
        LanScannerService service = service(
            List.of(relay("nolan", null)), Map.of(), Optional.empty(), List.of(), INLINE);

        service.startScan();
        assertThat(service.snapshot().machines()).isEmpty();
    }

    @Test
    void startScanDropsHostsAlreadyRegisteredAsLanServers() {
        LanScannerService service = service(
            List.of(relay("apalveien5", "192.168.3.0/24")),
            Map.of("192.168.3.0/24", List.of(
                new ScannedHost("192.168.3.10", List.of(2375), "docker01"),
                new ScannedHost("192.168.3.50", List.of(5000), "nas"))),
            Optional.empty(),
            List.of(new LanServer("nas", "192.168.3.50", false, null)), INLINE);

        service.startScan();
        assertThat(service.snapshot().machines()).extracting(DiscoveredLanMachine::ipAddress)
            .containsExactly("192.168.3.10");
    }

    @Test
    void startScanDropsHostsAlreadyRegisteredAsVpnPeers() {
        // An Ubuntu server already on the map carries its own LAN address; it must not resurface
        // as a candidate when its relay's LAN is swept.
        PeerConfiguration ubuntu = new PeerConfiguration(
            "media", "media", "10.13.13.7", "", MachineType.UBUNTU_SERVER, null, "192.168.3.10", null);
        LanScannerService service = service(
            List.of(relay("apalveien5", "192.168.3.0/24"), ubuntu),
            Map.of("192.168.3.0/24", List.of(
                new ScannedHost("192.168.3.10", List.of(2375), "media"),
                new ScannedHost("192.168.3.50", List.of(5000), "nas"))),
            Optional.empty(),
            List.of(), INLINE);

        service.startScan();
        assertThat(service.snapshot().machines()).extracting(DiscoveredLanMachine::ipAddress)
            .containsExactly("192.168.3.50");
    }

    @Test
    void startScanPublishesACompletionEvent() {
        LanScannerService service = service(List.of(), Map.of(), Optional.empty(), List.of(), INLINE);

        service.startScan();

        assertThat(events).containsExactly("lan-scan-updated");
    }

    @Test
    void ignoredMachineStaysInTheSnapshotButFlaggedIgnored() {
        LanScannerService service = service(
            List.of(relay("apalveien5", "192.168.3.0/24")),
            Map.of("192.168.3.0/24", List.of(new ScannedHost("192.168.3.111", List.of(9100), "printer"))),
            Optional.empty(), List.of(), INLINE);
        service.startScan();

        // Ignore happens between scans — no rescan. The flag is applied at snapshot() read time.
        service.ignore("apalveien5|192.168.3.111");

        assertThat(service.snapshot().machines())
            .extracting(DiscoveredLanMachine::ipAddress, DiscoveredLanMachine::ignored)
            .containsExactly(tuple("192.168.3.111", true));

        service.unignore("apalveien5|192.168.3.111");

        assertThat(service.snapshot().machines())
            .extracting(DiscoveredLanMachine::ipAddress, DiscoveredLanMachine::ignored)
            .containsExactly(tuple("192.168.3.111", false));
    }

    // --- targeted single-LAN scan (pick a LAN first, then scan just it) ---

    @Test
    void startScanForOneAnchorSweepsOnlyThatAnchorsCidr() {
        LanScannerService service = service(
            List.of(relay("apalveien5", "192.168.3.0/24"), relay("colina27", "192.168.1.0/24")),
            Map.of("192.168.1.0/24", List.of(new ScannedHost("192.168.1.5", List.of(80), "pool"))),
            Optional.of("172.31.0.0/16"), List.of(), INLINE);

        service.startScan("colina27");

        // Only Colina's LAN is touched — not the other relay's, not the server LAN.
        assertThat(sweptCidrs).containsExactly("192.168.1.0/24");
    }

    @Test
    void startScanForOneAnchorRefreshesThatLanAndPreservesTheOthers() {
        Map<String, List<ScannedHost>> scans = new HashMap<>();
        scans.put("192.168.3.0/24", List.of(new ScannedHost("192.168.3.10", List.of(2375), "docker01")));
        scans.put("192.168.1.0/24", List.of(new ScannedHost("192.168.1.5", List.of(80), "pool")));
        LanScannerService service = service(
            List.of(relay("apalveien5", "192.168.3.0/24"), relay("colina27", "192.168.1.0/24")),
            scans, Optional.empty(), List.of(), INLINE);
        service.startScan();   // both LANs populated

        // Colina's LAN changes; a targeted rescan of just Colina must pick that up and leave Apalveien alone.
        scans.put("192.168.1.0/24", List.of(new ScannedHost("192.168.1.6", List.of(443), "printer")));
        service.startScan("colina27");

        assertThat(service.snapshot().machines()).extracting(DiscoveredLanMachine::ipAddress)
            .containsExactlyInAnyOrder("192.168.3.10", "192.168.1.6");
    }

    @Test
    void startScanForAnUnknownAnchorIsANotFound() {
        LanScannerService service = service(
            List.of(relay("apalveien5", "192.168.3.0/24")), Map.of(), Optional.empty(), List.of(), INLINE);

        assertThatThrownBy(() -> service.startScan("ghost")).isInstanceOf(NotFoundException.class);
        assertThat(sweptCidrs).isEmpty();
    }

    @Test
    void snapshotForOneAnchorReturnsOnlyThatLansHosts() {
        LanScannerService service = service(
            List.of(relay("apalveien5", "192.168.3.0/24"), relay("colina27", "192.168.1.0/24")),
            Map.of("192.168.3.0/24", List.of(new ScannedHost("192.168.3.10", List.of(2375), "docker01")),
                   "192.168.1.0/24", List.of(new ScannedHost("192.168.1.5", List.of(80), "pool"))),
            Optional.empty(), List.of(), INLINE);
        service.startScan();

        assertThat(service.snapshot("colina27").machines()).extracting(DiscoveredLanMachine::ipAddress)
            .containsExactly("192.168.1.5");
    }

    @Test
    void scannableLansAreEveryRelayWithACidrThenTheServerLan() {
        LanScannerService service = service(
            List.of(relay("apalveien5", "192.168.3.0/24"), relay("nolan", null),
                    relay("colina27", "192.168.1.0/24")),
            Map.of(), Optional.of("172.31.0.0/16"), List.of(), INLINE);

        assertThat(service.scannableLans())
            .extracting(ScannableLan::anchor, ScannableLan::cidr)
            .containsExactly(
                tuple("apalveien5", "192.168.3.0/24"),
                tuple("colina27", "192.168.1.0/24"),
                tuple(LanAnchor.VAIER_SERVER_NAME, "172.31.0.0/16"));
    }

    @Test
    void aSecondStartScanWhileOneIsRunningIsIgnored() {
        // An executor that captures the task without running it leaves the first scan "in flight".
        List<Runnable> pending = new ArrayList<>();
        LanScannerService service = service(List.of(), Map.of(), Optional.empty(), List.of(), pending::add);

        service.startScan();
        service.startScan();

        assertThat(pending).hasSize(1);
        assertThat(service.snapshot().status()).isEqualTo(ScanStatus.SCANNING);
    }
}
