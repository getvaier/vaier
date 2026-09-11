package net.vaier.adapter.driven;

import net.vaier.domain.MachineContainerStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForPersistingContainerStandings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test-only in-memory stand-in for {@link ForPersistingContainerStandings}. Real deployments use
 * {@link ContainerStandingFileAdapter}; tests that only care about the <em>decisions</em> the domain makes
 * from what is remembered use this so they never touch a temp directory.
 */
public class InMemoryContainerStandingAdapter implements ForPersistingContainerStandings {

    private final Map<String, List<MachineContainerStanding>> standings = new LinkedHashMap<>();
    private final Map<String, Instant> boots = new LinkedHashMap<>();

    @Override
    public synchronized List<MachineContainerStanding> standingsFor(MachineId machineId) {
        return List.copyOf(standings.getOrDefault(machineId.value(), List.of()));
    }

    @Override
    public synchronized List<MachineContainerStanding> all() {
        List<MachineContainerStanding> everything = new ArrayList<>();
        standings.values().forEach(everything::addAll);
        return List.copyOf(everything);
    }

    @Override
    public synchronized void record(MachineId machineId, List<MachineContainerStanding> machineStandings) {
        if (machineStandings == null || machineStandings.isEmpty()) {
            standings.remove(machineId.value());
            return;
        }
        standings.put(machineId.value(), List.copyOf(machineStandings));
    }

    @Override
    public synchronized void recordBoot(MachineId machineId, Instant bootedAt) {
        boots.put(machineId.value(), bootedAt);
    }

    @Override
    public synchronized Optional<Instant> bootOf(MachineId machineId) {
        return Optional.ofNullable(boots.get(machineId.value()));
    }
}
