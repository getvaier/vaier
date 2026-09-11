package net.vaier.domain.port;

import net.vaier.domain.MachineId;

/**
 * Driven port for remembering which machines admins have already been told have <b>no default route</b>
 * (#357) — the latch behind {@link net.vaier.domain.MissingDefaultRouteTracker}.
 *
 * <p>Persisted for the reason {@link ForPersistingDiskPressureState} is: held in a field it would be wiped
 * by every redeploy, several a day here, and a latch that resets is a latch that re-sends the same email on
 * every deploy — or, with the opposite default, never sends it at all.
 *
 * <p>Only machines currently in the fault have an entry, so an empty store is the healthy state. Keyed on
 * {@link MachineId}, never on a machine's name: two machines in this fleet really are both called
 * "Printer", and a rename must not move one machine's alert onto another.
 */
public interface ForPersistingMissingDefaultRoutes {

    /** Whether admins have already been told that {@code machineId} has no default route. */
    boolean wasAlerted(MachineId machineId);

    /** Remember that admins have now been told about {@code machineId}. */
    void markAlerted(MachineId machineId);

    /** Forget {@code machineId} — it has a way out again, or is no longer in the fleet. */
    void clear(MachineId machineId);
}
