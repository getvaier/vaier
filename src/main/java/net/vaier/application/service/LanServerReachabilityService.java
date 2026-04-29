package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.domain.LanServer;
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
    private static final String SSE_TOPIC = "vpn-peers";
    private static final String SSE_EVENT = "lan-servers-updated";

    private final GetLanServersUseCase getLanServersUseCase;
    private final ForProbingTcp forProbingTcp;
    private final ForPublishingEvents forPublishingEvents;
    private final Map<String, Reachability> cache = new ConcurrentHashMap<>();

    public LanServerReachabilityService(GetLanServersUseCase getLanServersUseCase,
                                        ForProbingTcp forProbingTcp,
                                        ForPublishingEvents forPublishingEvents) {
        this.getLanServersUseCase = getLanServersUseCase;
        this.forProbingTcp = forProbingTcp;
        this.forPublishingEvents = forPublishingEvents;
    }

    @Override
    public Reachability getReachability(String lanServerName) {
        return cache.getOrDefault(lanServerName, Reachability.UNKNOWN);
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
            seen.add(server.name());
            cache.put(server.name(), probe(server.lanAddress()));
        }
        cache.keySet().retainAll(seen);

        if (!previous.equals(cache)) {
            // Wake up the Machines page so the status dot reflects the latest probe without
            // needing a manual refresh.
            forPublishingEvents.publish(SSE_TOPIC, SSE_EVENT, "");
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
