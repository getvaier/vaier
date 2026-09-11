package net.vaier.domain;

import net.vaier.domain.port.ForPersistingMissingDefaultRoutes;

/**
 * Decides, per machine, whether admins should hear that it has <b>lost its way out</b> (#357). It owns the
 * whole rule — first observation, transition, recovery, and what silence means — and reaches its own latch
 * through {@link ForPersistingMissingDefaultRoutes}; the watcher hands the port in and then only decides
 * whom to tell.
 *
 * <p>The two-state cousin of {@link RemoteDiskPressureTracker}. A disk fills gradually, so that tracker
 * needs bands to escalate through; a default route is there or it is not, so there is nothing to escalate
 * and the entire rule is three lines. What it keeps from the disk work is the part that was paid for in
 * silence: <b>the first observation speaks</b>. A fault that was already present when Vaier started is the
 * normal case here — nobody restarts Vaier the instant a route disappears — and a tracker whose first
 * sighting is a quiet baseline would never report it. That is only safe because the latch outlives the
 * process, so "first ever" genuinely means first ever.
 *
 * <p>{@link DefaultRouteStanding#UNKNOWN} moves nothing in either direction. It is not evidence of a fault,
 * and — the subtler half — it is not evidence of a recovery either: a sweep that could not read the machine
 * must never send the all-clear on a machine that is still broken.
 */
public class MissingDefaultRouteTracker {

    /** What Vaier should do about one machine this sweep. */
    public enum Outcome {
        /** Nothing has changed, or nothing is known — say nothing. */
        QUIET,
        /** The machine has no default route and admins have not been told — send the alert. */
        ALERT,
        /** The machine has a way out again after having been alerted about — send the all-clear. */
        RECOVERED
    }

    private final ForPersistingMissingDefaultRoutes alerted;

    public MissingDefaultRouteTracker(ForPersistingMissingDefaultRoutes alerted) {
        this.alerted = alerted;
    }

    /** Record where {@code machineId} stands on its default route, and decide what admins should hear. */
    public synchronized Outcome observe(MachineId machineId, DefaultRouteStanding standing) {
        if (standing == DefaultRouteStanding.UNKNOWN) {
            return Outcome.QUIET;
        }
        boolean standingAlert = alerted.wasAlerted(machineId);
        if (standing == DefaultRouteStanding.ABSENT) {
            if (standingAlert) {
                return Outcome.QUIET;
            }
            alerted.markAlerted(machineId);
            return Outcome.ALERT;
        }
        if (!standingAlert) {
            return Outcome.QUIET;
        }
        alerted.clear(machineId);
        return Outcome.RECOVERED;
    }
}
