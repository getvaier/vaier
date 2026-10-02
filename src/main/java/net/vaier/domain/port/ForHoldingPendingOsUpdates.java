package net.vaier.domain.port;

import net.vaier.domain.MachineId;
import net.vaier.domain.PendingOsUpdates;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Where each machine's last reading of its pending OS updates is held. A machine never read has none. */
public interface ForHoldingPendingOsUpdates {

    /** Keeps {@code reading}, returning the one it replaced. */
    Optional<PendingOsUpdates> record(PendingOsUpdates reading);

    List<PendingOsUpdates> getAll();

    /** Forgets every machine not in {@code machineIds} — a machine that left the fleet. */
    void retainOnly(Set<MachineId> machineIds);
}
