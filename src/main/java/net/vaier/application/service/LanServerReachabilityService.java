package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.NotifyAdminsOfPeerTransitionUseCase;
import net.vaier.application.PublishedServicesCacheInvalidator;
import net.vaier.domain.LanServer;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerSnapshot;
import net.vaier.domain.Reachability;
import net.vaier.domain.port.ForCheckingLanReachability;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForPingingHost;
import net.vaier.domain.port.ForProbingTcp;
import net.vaier.domain.port.ForProbingTcp.ProbeResult;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForRecordingLanReachability;
import org.springframework.stereotype.Service;

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
    // ICMP fallback fires only when every TCP probe times out. Printers / IoT / IPMI cards
    // often reply to ping without exposing ports 80/443/22, and this stops them from showing
    // as red on the Machines page.
    private static final int PING_TIMEOUT_MS = 1000;
    // A probe result must hold for this many consecutive cycles before it lands in the cache
    // (and therefore triggers an admin email). Dampens both warmup-after-restart blips and
    // ordinary network flapping. With the 30s scheduler, N=3 means a state must persist for
    // ~60s before we tell the operator about it.
    private static final int REQUIRED_CONSECUTIVE_PROBES = 3;
    private static final String SSE_TOPIC = "vpn-peers";
    private static final String SSE_EVENT = "lan-servers-updated";
    // A LAN host transitioning UP↔DOWN flips the host-state of every published service backed by
    // that host (issue #208), so the published-services cache must be invalidated and the
    // launchpad / services pages woken up — they subscribe to `service-updated` on this topic.
    private static final String PUBLISHED_SERVICES_SSE_TOPIC = "published-services";
    private static final String PUBLISHED_SERVICES_SSE_EVENT = "service-updated";

    private final ForGettingLanServers forGettingLanServers;
    private final ForProbingTcp forProbingTcp;
    private final ForPingingHost forPingingHost;
    private final ForPublishingEvents forPublishingEvents;
    private final NotifyAdminsOfPeerTransitionUseCase notifier;
    private final ForCheckingLanReachability cache;
    private final ForRecordingLanReachability recorder;
    private final PublishedServicesCacheInvalidator publishedServicesCacheInvalidator;
    // Debounce state is application-layer orchestration, not infrastructure cache: a candidate
    // result must hold for REQUIRED_CONSECUTIVE_PROBES consecutive cycles before the cache adapter
    // sees it. Keyed by lanAddress for the same rename-survives reason as the cache.
    private final Map<String, Reachability> pendingState = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingCount = new ConcurrentHashMap<>();

    public LanServerReachabilityService(ForGettingLanServers forGettingLanServers,
                                        ForProbingTcp forProbingTcp,
                                        ForPingingHost forPingingHost,
                                        ForPublishingEvents forPublishingEvents,
                                        NotifyAdminsOfPeerTransitionUseCase notifier,
                                        ForCheckingLanReachability cache,
                                        ForRecordingLanReachability recorder,
                                        PublishedServicesCacheInvalidator publishedServicesCacheInvalidator) {
        this.forGettingLanServers = forGettingLanServers;
        this.forProbingTcp = forProbingTcp;
        this.forPingingHost = forPingingHost;
        this.forPublishingEvents = forPublishingEvents;
        this.notifier = notifier;
        this.cache = cache;
        this.recorder = recorder;
        this.publishedServicesCacheInvalidator = publishedServicesCacheInvalidator;
    }

    @Override
    public Reachability getReachability(String lanAddress) {
        return cache.getReachability(lanAddress);
    }

    @Override
    public Long getLastSeenEpochSec(String lanAddress) {
        return cache.getLastSeenEpochSec(lanAddress);
    }

    @Override
    public synchronized void refreshAll() {
        Map<String, Reachability> previous = cache.snapshot();
        Set<String> seen = new HashSet<>();
        // Pingability is independent of Docker scrape state — probe every registered LAN
        // server. The UI combines this binary "is the host on the network" signal with the
        // Docker scrape result to produce green / yellow / red for Docker hosts.
        for (var view : forGettingLanServers.getAll()) {
            LanServer server = view.server();
            // Key everything by lanAddress: the host's reachability survives a rename.
            String key = server.lanAddress();
            seen.add(key);
            Reachability r = probe(server.lanAddress());
            // Only stamp lastSeen on a successful raw probe — independent of debounce, since
            // a one-off TCP success still proves the host was alive at that moment.
            if (r == Reachability.OK) {
                recorder.recordLastSeen(key, System.currentTimeMillis() / 1000);
            }
            // Debounce: only commit to the published cache (and therefore to the email path)
            // once the same probe result has held for REQUIRED_CONSECUTIVE_PROBES cycles.
            Reachability candidate = pendingState.get(key);
            int count = (candidate == r ? pendingCount.getOrDefault(key, 0) : 0) + 1;
            if (count >= REQUIRED_CONSECUTIVE_PROBES) {
                pendingState.remove(key);
                pendingCount.remove(key);
                Reachability prev = recorder.record(key, r);
                maybeNotifyTransition(server, prev, r);
            } else {
                pendingState.put(key, r);
                pendingCount.put(key, count);
            }
        }
        recorder.retainOnly(seen);
        pendingState.keySet().retainAll(seen);
        pendingCount.keySet().retainAll(seen);

        if (!previous.equals(cache.snapshot())) {
            // Wake up the Machines page so the icon colour reflects the latest confirmed
            // probe without needing a manual refresh.
            forPublishingEvents.publish(SSE_TOPIC, SSE_EVENT, "");
            // A LAN host's reachability also feeds ReverseProxyRoute.hostState via the
            // ForCheckingLanReachability port — drop the published-services cache so the next
            // launchpad / services fetch recomputes state, and wake those pages too (issue #208).
            publishedServicesCacheInvalidator.invalidatePublishedServicesCache();
            forPublishingEvents.publish(PUBLISHED_SERVICES_SSE_TOPIC, PUBLISHED_SERVICES_SSE_EVENT, "");
        }
    }

    private void maybeNotifyTransition(LanServer server, Reachability previous, Reachability current) {
        // A null previous means this is the first observation for this server (Vaier just
        // started, or the server was just registered) — baseline silently to avoid an email
        // storm on restart. Same rule the VPN PeerConnectivityTracker applies.
        if (previous == null || previous == current) return;
        boolean connected = current == Reachability.OK;
        Long lastSeen = cache.getLastSeenEpochSec(server.lanAddress());
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
        // Every probed TCP port timed out. Fall back to ICMP for hosts that don't expose
        // any of those ports — printers, IoT devices, IPMI cards. Keeps the fast path on
        // healthy hosts (TCP wins early-exit, no ping subprocess spawned).
        if (forPingingHost.isReachable(lanAddress, PING_TIMEOUT_MS)) {
            return Reachability.OK;
        }
        return Reachability.DOWN;
    }
}
