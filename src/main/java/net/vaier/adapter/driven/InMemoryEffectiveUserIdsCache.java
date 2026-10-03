package net.vaier.adapter.driven;

import net.vaier.domain.EffectiveUserIds;
import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForHoldingEffectiveUserIds;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** What the rounds last read of each machine's effective user ids, held in memory and keyed by identity. */
@Component
public class InMemoryEffectiveUserIdsCache implements ForHoldingEffectiveUserIds {

    private final Map<MachineId, EffectiveUserIds> ids = new ConcurrentHashMap<>();

    @Override
    public void record(MachineId machineId, EffectiveUserIds read) {
        ids.put(machineId, read);
    }

    @Override
    public Optional<EffectiveUserIds> get(MachineId machineId) {
        return Optional.ofNullable(ids.get(machineId));
    }

    @Override
    public void retainOnly(Set<MachineId> machineIds) {
        ids.keySet().retainAll(machineIds);
    }
}
