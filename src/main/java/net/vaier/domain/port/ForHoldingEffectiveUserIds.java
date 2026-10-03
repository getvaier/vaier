package net.vaier.domain.port;

import net.vaier.domain.EffectiveUserIds;
import net.vaier.domain.MachineId;

import java.util.Optional;
import java.util.Set;

/** Where the fleet's rounds keep each machine's {@link EffectiveUserIds}. A machine never read has none. */
public interface ForHoldingEffectiveUserIds {

    void record(MachineId machineId, EffectiveUserIds ids);

    Optional<EffectiveUserIds> get(MachineId machineId);

    /** Forgets every machine not in {@code machineIds} — a machine that left the fleet. */
    void retainOnly(Set<MachineId> machineIds);
}
