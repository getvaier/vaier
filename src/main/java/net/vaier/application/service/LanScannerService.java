package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetDiscoveredLanMachinesUseCase;
import net.vaier.application.IgnoreLanMachineUseCase;
import net.vaier.application.ListScannableLansUseCase;
import net.vaier.application.ScanLanAnchorUseCase;
import net.vaier.application.ScanLanUseCase;
import net.vaier.application.UnignoreLanMachineUseCase;
import net.vaier.domain.DiscoveredLanMachine;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.port.ForForgettingDiscoveredLanMachines;
import net.vaier.domain.port.ForGettingDiscoveredLanMachines;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForManagingIgnoredLanMachines;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import net.vaier.domain.port.ForScanningLan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
public class LanScannerService implements ScanLanUseCase, ScanLanAnchorUseCase,
    ListScannableLansUseCase, GetDiscoveredLanMachinesUseCase,
    IgnoreLanMachineUseCase, UnignoreLanMachineUseCase,
    ForGettingDiscoveredLanMachines, ForForgettingDiscoveredLanMachines {

    private static final String SSE_TOPIC = "vpn-peers";
    private static final String SSE_EVENT = "lan-scan-updated";

    private final ForScanningLan scanner;
    private final ForGettingPeerConfigurations peerConfigurations;
    private final ForResolvingServerLanCidr serverLanCidr;
    private final ForGettingLanServers lanServers;
    private final ForManagingIgnoredLanMachines ignoredMachines;
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
                             ForManagingIgnoredLanMachines ignoredMachines,
                             ForPublishingEvents events) {
        // A single-thread executor: scans are serialised and never pile up on the request threads.
        this(scanner, peerConfigurations, serverLanCidr, lanServers, ignoredMachines, events,
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
                      ForManagingIgnoredLanMachines ignoredMachines,
                      ForPublishingEvents events,
                      Executor scanExecutor) {
        this.scanner = scanner;
        this.peerConfigurations = peerConfigurations;
        this.serverLanCidr = serverLanCidr;
        this.lanServers = lanServers;
        this.ignoredMachines = ignoredMachines;
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
    public void startScan(String anchorKey) {
        // Resolve the picked LAN to its CIDR up front (a domain decision) so an unknown anchor is a
        // 404 before any sweep starts, rather than a silently empty result.
        LanAnchor anchor = LanAnchor.byKey(anchorKey,
                peerConfigurations.getAllPeerConfigs(), serverLanCidr.resolve())
            .orElseThrow(() -> new NotFoundException("No scannable LAN named \"" + anchorKey + "\""));
        if (!scanning.compareAndSet(false, true)) {
            return; // a scan is already in flight — don't queue another
        }
        scanExecutor.execute(() -> {
            try {
                Set<String> registered = registeredAddresses(peerConfigurations.getAllPeerConfigs());
                List<DiscoveredLanMachine> fresh = scanAnchor(anchor, registered);
                // Replace only this LAN's slice of the snapshot; every other LAN's results stand.
                List<DiscoveredLanMachine> merged = new ArrayList<>(lastResults.stream()
                    .filter(m -> !m.isOnLan(anchor.anchorKey()))
                    .toList());
                merged.addAll(fresh);
                lastResults = List.copyOf(merged);
                lastScanCompleted = Instant.now();
            } catch (RuntimeException e) {
                log.warn("Targeted LAN scan failed; keeping previous results: {}", e.getMessage());
            } finally {
                scanning.set(false);
                events.publish(SSE_TOPIC, SSE_EVENT, "");
            }
        });
    }

    @Override
    public List<ScannableLan> scannableLans() {
        return LanAnchor.scannable(peerConfigurations.getAllPeerConfigs(), serverLanCidr.resolve()).stream()
            .map(a -> new ScannableLan(a.anchorKey(), a.name(), a.cidr()))
            .toList();
    }

    @Override
    public LanScanSnapshot snapshot() {
        ScanStatus status = scanning.get() ? ScanStatus.SCANNING : ScanStatus.IDLE;
        // Ignore happens between scans (no rescan), so the ignored flag is applied at read time —
        // the domain owns the decision. Ignored hosts stay visible so the UI can offer Unignore.
        Set<String> ignoredKeys = ignoredMachines.getIgnoredKeys();
        List<DiscoveredLanMachine> flagged = lastResults.stream()
            .map(m -> m.withIgnored(ignoredKeys))
            .toList();
        return new LanScanSnapshot(status, flagged, lastScanCompleted);
    }

    @Override
    public LanScanSnapshot snapshot(String anchorKey) {
        LanScanSnapshot all = snapshot();
        List<DiscoveredLanMachine> scoped = all.machines().stream()
            .filter(m -> m.isOnLan(anchorKey))
            .toList();
        return new LanScanSnapshot(all.status(), scoped, all.lastScanCompleted());
    }

    @Override
    public Optional<DiscoveredLanMachine> findByIpAddress(String ipAddress) {
        // Read-side of the adoption port: the LAN-server domain looks the candidate up here so it
        // never depends on this scanner's inbound use case. First match wins if an address somehow
        // appears on two relay LANs.
        return lastResults.stream()
            .filter(m -> m.ipAddress().equals(ipAddress))
            .findFirst();
    }

    @Override
    public void forget(String ipAddress) {
        // Write-side of the adoption port: drop the adopted host from the snapshot so it stops
        // surfacing as a candidate immediately, rather than waiting for the next sweep to filter it
        // out as already-registered.
        lastResults = lastResults.stream()
            .filter(m -> !m.ipAddress().equals(ipAddress))
            .toList();
    }

    @Override
    public void ignore(String ignoreKey) {
        ignoredMachines.ignore(ignoreKey);
    }

    @Override
    public void unignore(String ignoreKey) {
        ignoredMachines.unignore(ignoreKey);
    }

    private List<DiscoveredLanMachine> performScan() {
        List<PeerConfiguration> peers = peerConfigurations.getAllPeerConfigs();
        Set<String> registered = registeredAddresses(peers);
        // Each scan is a ~20s relay-side LAN sweep, so the LANs are swept concurrently: wall-time
        // stays at a single sweep rather than the sum across every relay. Encounter order (relay
        // peers in config order, then the server LAN) is preserved by the ordered stream. The
        // enumeration itself is the domain's ({@link LanAnchor#scannable}), shared with the picker.
        return LanAnchor.scannable(peers, serverLanCidr.resolve()).parallelStream()
            .flatMap(anchor -> scanAnchor(anchor, registered).stream())
            .toList();
    }

    /** Sweep one LAN and tag its responsive, not-yet-registered hosts with the anchor's key. */
    private List<DiscoveredLanMachine> scanAnchor(LanAnchor anchor, Set<String> registered) {
        return scanner.scan(anchor.cidr()).stream()
            .map(h -> new DiscoveredLanMachine(h.ipAddress(), h.hostname(), h.openPorts(), anchor.anchorKey()))
            .filter(m -> !m.isAlreadyRegistered(registered))
            .toList();
    }

    /**
     * The addresses already owned by a registered machine — LAN servers and VPN peers (relays and
     * Ubuntu servers carry a LAN address) — so a host on the map never resurfaces as a candidate.
     */
    private Set<String> registeredAddresses(List<PeerConfiguration> peers) {
        Set<String> registered = new HashSet<>();
        lanServers.getAll().stream()
            .map(ForGettingLanServers.LanServerView::server)
            .map(LanServer::lanAddress)
            .filter(a -> a != null && !a.isBlank())
            .forEach(registered::add);
        peers.stream()
            .map(PeerConfiguration::lanAddress)
            .filter(a -> a != null && !a.isBlank())
            .forEach(registered::add);
        return registered;
    }
}
