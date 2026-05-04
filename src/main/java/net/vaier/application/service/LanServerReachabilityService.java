package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.NotifyAdminsOfPeerTransitionUseCase;
import net.vaier.domain.LanServer;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerSnapshot;
import net.vaier.domain.port.ForProbingTcp;
import net.vaier.domain.port.ForProbingTcp.ProbeResult;
import net.vaier.domain.port.ForPublishingEvents;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class LanServerReachabilityService implements GetLanServerReachabilityUseCase {

    private static final List<Integer> PROBE_PORTS = List.of(80, 443, 22);
    private static final int PROBE_TIMEOUT_MS = 1000;
    // A probe result must hold for this many consecutive cycles before it lands in the cache
    // (and therefore triggers an admin email). Dampens both warmup-after-restart blips and
    // ordinary network flapping. With the 30s scheduler, N=3 means a state must persist for
    // ~60s before we tell the operator about it.
    private static final int REQUIRED_CONSECUTIVE_PROBES = 3;
    private static final String SSE_TOPIC = "vpn-peers";
    private static final String SSE_EVENT = "lan-servers-updated";

    private final GetLanServersUseCase getLanServersUseCase;
    private final ForProbingTcp forProbingTcp;
    private final ForPublishingEvents forPublishingEvents;
    private final NotifyAdminsOfPeerTransitionUseCase notifier;
    private final Map<String, Reachability> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenEpochSec = new ConcurrentHashMap<>();
    private final Map<String, Reachability> pendingState = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingCount = new ConcurrentHashMap<>();

    public LanServerReachabilityService(GetLanServersUseCase getLanServersUseCase,
                                        ForProbingTcp forProbingTcp,
                                        ForPublishingEvents forPublishingEvents,
                                        NotifyAdminsOfPeerTransitionUseCase notifier) {
        this.getLanServersUseCase = getLanServersUseCase;
        this.forProbingTcp = forProbingTcp;
        this.forPublishingEvents = forPublishingEvents;
        this.notifier = notifier;
    }

    @Override
    public Reachability getReachability(String lanServerName) {
        return cache.getOrDefault(lanServerName, Reachability.UNKNOWN);
    }

    @Override
    public Long getLastSeenEpochSec(String lanServerName) {
        return lastSeenEpochSec.get(lanServerName);
    }

    @Override
    public synchronized void refreshAll() {
        Map<String, Reachability> previous = new HashMap<>(cache);
        Set<String> seen = new HashSet<>();
        // Pingability is independent of Docker scrape state — probe every registered LAN
        // server. The UI combines this binary "is the host on the network" signal with the
        // Docker scrape result to produce green / yellow / red for Docker hosts.
        for (var view : getLanServersUseCase.getAll()) {
            LanServer server = view.server();
            String name = server.name();
            seen.add(name);
            Reachability r = probe(server.lanAddress());
            // Only stamp lastSeen on a successful raw probe — independent of debounce, since
            // a one-off TCP success still proves the host was alive at that moment.
            if (r == Reachability.OK) {
                lastSeenEpochSec.put(name, System.currentTimeMillis() / 1000);
            }
            // Debounce: only commit to the published cache (and therefore to the email path)
            // once the same probe result has held for REQUIRED_CONSECUTIVE_PROBES cycles.
            Reachability candidate = pendingState.get(name);
            int count = (candidate == r ? pendingCount.getOrDefault(name, 0) : 0) + 1;
            if (count >= REQUIRED_CONSECUTIVE_PROBES) {
                pendingState.remove(name);
                pendingCount.remove(name);
                Reachability prev = cache.put(name, r);
                maybeNotifyTransition(server, prev, r);
            } else {
                pendingState.put(name, r);
                pendingCount.put(name, count);
            }
        }
        cache.keySet().retainAll(seen);
        lastSeenEpochSec.keySet().retainAll(seen);
        pendingState.keySet().retainAll(seen);
        pendingCount.keySet().retainAll(seen);

        if (!previous.equals(cache)) {
            // Wake up the Machines page so the icon colour reflects the latest confirmed
            // probe without needing a manual refresh.
            forPublishingEvents.publish(SSE_TOPIC, SSE_EVENT, "");
        }
    }

    private void maybeNotifyTransition(LanServer server, Reachability previous, Reachability current) {
        // A null previous means this is the first observation for this server (Vaier just
        // started, or the server was just registered) — baseline silently to avoid an email
        // storm on restart. Same rule the VPN PeerConnectivityTracker applies.
        if (previous == null || previous == current) return;
        boolean connected = current == Reachability.OK;
        Long lastSeen = lastSeenEpochSec.get(server.name());
        PeerSnapshot snapshot = new PeerSnapshot(
                server.name(),
                MachineType.LAN_SERVER,
                connected,
                lastSeen != null ? lastSeen : 0L,
                server.lanAddress());
        try {
            notifier.notifyAdmins(snapshot);
        } catch (Exception e) {
            log.warn("Failed to notify admins for LAN server {}: {}", server.name(), e.getMessage());
        }
    }

    private Reachability probe(String lanAddress) {
        for (int port : PROBE_PORTS) {
            ProbeResult r = forProbingTcp.probe(lanAddress, port, PROBE_TIMEOUT_MS);
            if (r == ProbeResult.CONNECTED || r == ProbeResult.REFUSED) {
                return Reachability.OK;
            }
        }
        return Reachability.DOWN;
    }
}
