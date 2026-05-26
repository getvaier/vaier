package net.vaier.domain.port;

import net.vaier.domain.Reachability;

import java.util.Set;

public interface ForRecordingLanReachability {

    /**
     * Commit a debounced reachability result for {@code lanAddress}, returning the previously
     * recorded value (or {@code null} if this address was unknown to the cache). The
     * orchestrator uses the returned value to decide whether to notify admins of a transition.
     */
    Reachability record(String lanAddress, Reachability reachability);

    /**
     * Stamp the last-successful-probe time for {@code lanAddress}. Called only when a raw probe
     * succeeded, independent of debounce.
     */
    void recordLastSeen(String lanAddress, long epochSec);

    /**
     * Drop every cached entry whose address is not in {@code addresses}. Called after a probe
     * sweep to evict state for LAN servers that were deregistered while Vaier was running.
     */
    void retainOnly(Set<String> addresses);
}
