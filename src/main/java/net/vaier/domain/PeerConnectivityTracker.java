package net.vaier.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PeerConnectivityTracker {

    private final Map<String, Boolean> lastKnownState = new HashMap<>();

    public synchronized List<PeerSnapshot> update(List<PeerSnapshot> current) {
        return update(current, Set.of());
    }

    /**
     * As {@link #update(List)}, but a peer named in {@code switchedOffOnPurpose} is only tracked: its going
     * and coming back are expected, so neither is reported.
     */
    public synchronized List<PeerSnapshot> update(List<PeerSnapshot> current, Set<String> switchedOffOnPurpose) {
        List<PeerSnapshot> transitions = new ArrayList<>();
        for (PeerSnapshot snapshot : current) {
            Boolean prev = lastKnownState.get(snapshot.name());
            if (prev != null && prev != snapshot.connected() && !switchedOffOnPurpose.contains(snapshot.name())) {
                transitions.add(snapshot);
            }
            lastKnownState.put(snapshot.name(), snapshot.connected());
        }
        return Collections.unmodifiableList(transitions);
    }
}
