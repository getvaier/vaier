package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PeerConnectivityTrackerTest {

    private final PeerConnectivityTracker tracker = new PeerConnectivityTracker();

    private static PeerSnapshot snap(String name, boolean connected) {
        return new PeerSnapshot(name, PeerType.UBUNTU_SERVER, connected, 0L, null);
    }

    @Test
    void firstObservation_isTreatedAsBaseline_andEmitsNoTransition() {
        List<PeerSnapshot> transitions = tracker.update(List.of(snap("server-1", true)));

        assertThat(transitions).isEmpty();
    }

    @Test
    void connectedToDisconnected_emitsTransition() {
        tracker.update(List.of(snap("server-1", true)));

        List<PeerSnapshot> transitions = tracker.update(List.of(snap("server-1", false)));

        assertThat(transitions).hasSize(1);
        assertThat(transitions.get(0).name()).isEqualTo("server-1");
        assertThat(transitions.get(0).connected()).isFalse();
    }

    @Test
    void disconnectedToConnected_emitsTransition() {
        tracker.update(List.of(snap("server-1", false)));

        List<PeerSnapshot> transitions = tracker.update(List.of(snap("server-1", true)));

        assertThat(transitions).hasSize(1);
        assertThat(transitions.get(0).connected()).isTrue();
    }

    @Test
    void sameStateRepeatedly_emitsNothing() {
        tracker.update(List.of(snap("server-1", true)));
        tracker.update(List.of(snap("server-1", true)));

        List<PeerSnapshot> transitions = tracker.update(List.of(snap("server-1", true)));

        assertThat(transitions).isEmpty();
    }

    @Test
    void peerAppearingForTheFirstTime_isBaseline_evenIfOtherPeersAreTracked() {
        tracker.update(List.of(snap("a", true)));

        List<PeerSnapshot> transitions = tracker.update(List.of(snap("a", true), snap("b", true)));

        assertThat(transitions).isEmpty();
    }

    @Test
    void multipleTransitionsInSameTick_areAllEmitted() {
        tracker.update(List.of(snap("a", true), snap("b", true)));

        List<PeerSnapshot> transitions = tracker.update(List.of(snap("a", false), snap("b", false)));

        assertThat(transitions).extracting(PeerSnapshot::name)
                .containsExactlyInAnyOrder("a", "b");
        assertThat(transitions).allSatisfy(t -> assertThat(t.connected()).isFalse());
    }

    @Test
    void peerDisappearingFromList_emitsNoTransition() {
        tracker.update(List.of(snap("a", true)));

        List<PeerSnapshot> transitions = tracker.update(List.of());

        assertThat(transitions).isEmpty();
    }
}
