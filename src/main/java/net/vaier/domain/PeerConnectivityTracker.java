package net.vaier.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PeerConnectivityTracker {

    private final Map<String, Boolean> lastKnownState = new HashMap<>();

    public synchronized List<PeerSnapshot> update(List<PeerSnapshot> current) {
        List<PeerSnapshot> transitions = new ArrayList<>();
        for (PeerSnapshot snapshot : current) {
            Boolean prev = lastKnownState.get(snapshot.name());
            if (prev != null && prev != snapshot.connected()) {
                transitions.add(snapshot);
            }
            lastKnownState.put(snapshot.name(), snapshot.connected());
        }
        return Collections.unmodifiableList(transitions);
    }
}
