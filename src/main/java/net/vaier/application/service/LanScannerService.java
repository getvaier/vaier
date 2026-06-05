package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetDiscoveredLanMachinesUseCase;
import net.vaier.application.ScanLanUseCase;
import net.vaier.domain.DiscoveredLanMachine;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import net.vaier.domain.port.ForScanningLan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Discovers candidate LAN machines (issue #246) by sweeping every relay peer's {@code lanCidr} and
 * the Vaier server's own LAN CIDR, dropping any host already registered as a {@link LanServer}.
 *
 * <p>A LAN sweep is intrusive and slow (~20s per /24), so it runs <em>on demand and
 * asynchronously</em>: {@link #startScan()} kicks off a background sweep and returns immediately,
 * the latest result is read via {@link #snapshot()}, and a {@code lan-scan-updated} SSE event fires
 * on completion so the UI refreshes itself. A scan already in flight is not started again.
 * Orchestration only — the probe lives in {@link ForScanningLan} and the read-offs (role guess,
 * already-registered) live on {@link DiscoveredLanMachine}.
 */
@Service
@Slf4j
public class LanScannerService implements ScanLanUseCase, GetDiscoveredLanMachinesUseCase {

    private static final String SSE_TOPIC = "vpn-peers";
    private static final String SSE_EVENT = "lan-scan-updated";

    private final ForScanningLan scanner;
    private final ForGettingPeerConfigurations peerConfigurations;
    private final ForResolvingServerLanCidr serverLanCidr;
    private final ForGettingLanServers lanServers;
    private final ForPublishingEvents events;
    private final Executor scanExecutor;

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile List<DiscoveredLanMachine> lastResults = List.of();
    private volatile Instant lastScanCompleted;

    @Autowired
    public LanScannerService(ForScanningLan scanner,
                             ForGettingPeerConfigurations peerConfigurations,
                             ForResolvingServerLanCidr serverLanCidr,
                             ForGettingLanServers lanServers,
                             ForPublishingEvents events) {
        // A single-thread executor: scans are serialised and never pile up on the request threads.
        this(scanner, peerConfigurations, serverLanCidr, lanServers, events,
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "lan-scanner");
                t.setDaemon(true);
                return t;
            }));
    }

    LanScannerService(ForScanningLan scanner,
                      ForGettingPeerConfigurations peerConfigurations,
                      ForResolvingServerLanCidr serverLanCidr,
                      ForGettingLanServers lanServers,
                      ForPublishingEvents events,
                      Executor scanExecutor) {
        this.scanner = scanner;
        this.peerConfigurations = peerConfigurations;
        this.serverLanCidr = serverLanCidr;
        this.lanServers = lanServers;
        this.events = events;
        this.scanExecutor = scanExecutor;
    }

    @Override
    public void startScan() {
        if (!scanning.compareAndSet(false, true)) {
            return; // a scan is already in flight — don't queue another
        }
        scanExecutor.execute(() -> {
            try {
                lastResults = performScan();
                lastScanCompleted = Instant.now();
            } catch (RuntimeException e) {
                log.warn("LAN scan failed; keeping previous results: {}", e.getMessage());
            } finally {
                scanning.set(false);
                events.publish(SSE_TOPIC, SSE_EVENT, "");
            }
        });
    }

    @Override
    public LanScanSnapshot snapshot() {
        ScanStatus status = scanning.get() ? ScanStatus.SCANNING : ScanStatus.IDLE;
        return new LanScanSnapshot(status, lastResults, lastScanCompleted);
    }

    private List<DiscoveredLanMachine> performScan() {
        List<PeerConfiguration> peers = peerConfigurations.getAllPeerConfigs();

        // A host is "already registered" if any machine on the map owns its address — both
        // registered LAN servers and VPN peers (relays/Ubuntu servers carry a LAN address).
        Set<String> registeredAddresses = new HashSet<>();
        lanServers.getAll().stream()
            .map(ForGettingLanServers.LanServerView::server)
            .map(LanServer::lanAddress)
            .filter(a -> a != null && !a.isBlank())
            .forEach(registeredAddresses::add);
        peers.stream()
            .map(PeerConfiguration::lanAddress)
            .filter(a -> a != null && !a.isBlank())
            .forEach(registeredAddresses::add);

        List<ScanTarget> targets = new ArrayList<>();
        for (PeerConfiguration peer : peers) {
            if (peer.lanCidr() == null || peer.lanCidr().isBlank()) continue;
            targets.add(new ScanTarget(peer.lanCidr(), peer.id()));
        }
        serverLanCidr.resolve()
            .ifPresent(cidr -> targets.add(new ScanTarget(cidr, LanAnchor.VAIER_SERVER_NAME)));

        // Each scan is a ~20s relay-side LAN sweep, so the LANs are swept concurrently: wall-time
        // stays at a single sweep rather than the sum across every relay. Encounter order (relay
        // peers in config order, then the server LAN) is preserved by the ordered stream.
        return targets.parallelStream()
            .flatMap(t -> scanner.scan(t.cidr()).stream()
                .map(h -> new DiscoveredLanMachine(h.ipAddress(), h.hostname(), h.openPorts(), t.anchor())))
            .filter(m -> !m.isAlreadyRegistered(registeredAddresses))
            .toList();
    }

    private record ScanTarget(String cidr, String anchor) {}
}
