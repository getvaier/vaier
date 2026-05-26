package net.vaier.adapter.driven;

import net.vaier.domain.Reachability;
import net.vaier.domain.port.ForCheckingLanReachability;
import net.vaier.domain.port.ForRecordingLanReachability;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory state for the LAN-reachability probe. Owns the two maps that used to live on
 * {@code LanServerReachabilityService} so the orchestrator no longer holds infrastructure
 * state directly: the cache adapter is the single source of truth, the orchestrator writes
 * via {@link ForRecordingLanReachability} and other services read via
 * {@link ForCheckingLanReachability}. All entries are keyed by {@code lanAddress} (not name)
 * so a host's history survives a rename.
 */
@Component
public class InMemoryLanReachabilityCache implements ForCheckingLanReachability, ForRecordingLanReachability {

    private final Map<String, Reachability> reachability = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenEpochSec = new ConcurrentHashMap<>();

    @Override
    public Reachability getReachability(String lanAddress) {
        return reachability.getOrDefault(lanAddress, Reachability.UNKNOWN);
    }

    @Override
    public Long getLastSeenEpochSec(String lanAddress) {
        return lastSeenEpochSec.get(lanAddress);
    }

    @Override
    public Map<String, Reachability> snapshot() {
        return Map.copyOf(reachability);
    }

    @Override
    public Reachability record(String lanAddress, Reachability r) {
        return reachability.put(lanAddress, r);
    }

    @Override
    public void recordLastSeen(String lanAddress, long epochSec) {
        lastSeenEpochSec.put(lanAddress, epochSec);
    }

    @Override
    public void retainOnly(Set<String> addresses) {
        reachability.keySet().retainAll(addresses);
        lastSeenEpochSec.keySet().retainAll(addresses);
    }
}
