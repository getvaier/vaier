package net.vaier.adapter.driven;

import net.vaier.domain.MachineId;
import net.vaier.domain.PendingOsUpdates;
import net.vaier.domain.port.ForHoldingPendingOsUpdates;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** What the sweep last read of each machine's pending OS updates, held in memory and keyed by identity. */
@Component
public class InMemoryPendingOsUpdatesCache implements ForHoldingPendingOsUpdates {

    private final Map<MachineId, PendingOsUpdates> readings = new ConcurrentHashMap<>();

    @Override
    public Optional<PendingOsUpdates> record(PendingOsUpdates reading) {
        return Optional.ofNullable(readings.put(reading.machineId(), reading));
    }

    @Override
    public List<PendingOsUpdates> getAll() {
        return List.copyOf(readings.values());
    }

    @Override
    public void retainOnly(Set<MachineId> machineIds) {
        readings.keySet().retainAll(machineIds);
    }
}
