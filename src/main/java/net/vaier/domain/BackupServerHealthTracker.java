package net.vaier.domain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-server backup-server-reachability state, so the {@code BackupServerWatcher} alerts admins only when a
 * server <em>crosses</em> from healthy to down (or back) — not on every sweep. It is the fleet-backup sibling
 * of {@link BackupFailureTracker} and {@link RemoteDiskPressureTracker}: each server gets its own independent
 * transition state, so one server's outage never disturbs another's.
 *
 * <p>Two things distinguish it from the disk/failure trackers:
 * <ul>
 *   <li><b>Two-strike hysteresis into DOWN.</b> A single failed probe is a blip (a momentary VPN hiccup, a
 *       borg container mid-restart) and must never page; only a <em>second consecutive</em> failed probe
 *       crosses to {@code CROSSED_TO_DOWN}. A single successful probe immediately restores healthy — recovery
 *       is asymmetric, because a server that answers is unambiguously back. One success anywhere in a failing
 *       streak resets the strike count, so two failures must be genuinely back-to-back to page.</li>
 *   <li><b>Seeded assumed-healthy.</b> Each server's baseline is healthy, mirroring {@link BackupFailureTracker}.
 *       This keeps a Vaier restart quiet for servers that are up, and is <em>deliberately</em> the right thing
 *       for a server that is already down at startup: it takes two ticks to confirm and then pages exactly once
 *       — a real, actionable "your backup server is down" — rather than either staying silent forever or
 *       re-paging on every restart. There is no null baseline here (unlike the disk trackers): a down backup
 *       server <em>is</em> news, whereas a disk merely observed full at startup is not.</li>
 * </ul>
 */
public class BackupServerHealthTracker {

    /** The minimum consecutive failed probes required before crossing to DOWN, so a single blip never pages. */
    private static final int REQUIRED_CONSECUTIVE_FAILURES = 2;

    /** Whether the latest transition crossed to down, back to healthy, or stayed put. */
    public enum Transition { NONE, CROSSED_TO_DOWN, CROSSED_TO_HEALTHY }

    private final Map<String, ServerHealthState> perServer = new ConcurrentHashMap<>();

    /**
     * Record whether {@code serverName} was {@code healthyNow} on this sweep and report whether that crossed a
     * boundary since the server's previous sweep. Requires two consecutive failures to cross to down; one
     * success restores healthy.
     */
    public Transition update(String serverName, boolean healthyNow) {
        return perServer.computeIfAbsent(serverName, s -> new ServerHealthState()).update(healthyNow);
    }

    /**
     * Drop {@code serverName}'s tracked state — call when a Backup server is deleted, so the map never grows
     * unbounded and a re-created server of the same name starts fresh (seeded healthy).
     */
    public void forget(String serverName) {
        perServer.remove(serverName);
    }

    /** A single server's health state, seeded healthy so an up server is quiet and a down one pages once. */
    private static final class ServerHealthState {

        private boolean healthy = true;
        private int consecutiveFailures = 0;

        synchronized Transition update(boolean healthyNow) {
            if (healthyNow) {
                consecutiveFailures = 0;
                if (!healthy) {
                    healthy = true;
                    return Transition.CROSSED_TO_HEALTHY;
                }
                return Transition.NONE;
            }
            consecutiveFailures++;
            if (healthy && consecutiveFailures >= REQUIRED_CONSECUTIVE_FAILURES) {
                healthy = false;
                return Transition.CROSSED_TO_DOWN;
            }
            return Transition.NONE;
        }
    }
}
