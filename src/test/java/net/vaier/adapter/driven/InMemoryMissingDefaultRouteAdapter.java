package net.vaier.adapter.driven;

import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForPersistingMissingDefaultRoutes;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Test-only in-memory stand-in for {@link ForPersistingMissingDefaultRoutes}. Real deployments use
 * {@link MissingDefaultRouteFileAdapter}; tests that only care about the <em>decisions</em> the domain makes
 * from the stored latch use this so they never touch a temp directory.
 */
public class InMemoryMissingDefaultRouteAdapter implements ForPersistingMissingDefaultRoutes {

    private final Set<String> alerted = new LinkedHashSet<>();

    @Override
    public synchronized boolean wasAlerted(MachineId machineId) {
        return alerted.contains(machineId.value());
    }

    @Override
    public synchronized void markAlerted(MachineId machineId) {
        alerted.add(machineId.value());
    }

    @Override
    public synchronized void clear(MachineId machineId) {
        alerted.remove(machineId.value());
    }
}
