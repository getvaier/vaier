package net.vaier.domain;

/**
 * Remembers whether the host disk was last seen above its alert threshold, so the watcher only
 * notifies admins when the disk crosses a boundary — not on every poll. The very first
 * observation after startup is a baseline and never reports a transition, mirroring
 * {@link PeerConnectivityTracker}'s restart-quiet behaviour.
 */
public class DiskPressureTracker {

    public enum Transition { NONE, CROSSED_ABOVE, CROSSED_BELOW }

    private Boolean lastAboveThreshold;

    public synchronized Transition update(boolean aboveThreshold) {
        Transition transition = Transition.NONE;
        if (lastAboveThreshold != null && lastAboveThreshold != aboveThreshold) {
            transition = aboveThreshold ? Transition.CROSSED_ABOVE : Transition.CROSSED_BELOW;
        }
        lastAboveThreshold = aboveThreshold;
        return transition;
    }
}
