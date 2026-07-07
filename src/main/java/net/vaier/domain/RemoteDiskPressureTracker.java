package net.vaier.domain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-machine remote-disk-pressure state, so the {@code RemoteDiskWatcher} alerts only when a machine
 * crosses a boundary — not on every poll. It is the multi-machine sibling of {@link DiskPressureTracker}
 * (which tracks the single Vaier-host disk): each machine gets its own {@link DiskPressureTracker}, so
 * the first observation for a machine is a baseline and restarts never produce noise, and one machine's
 * transition never disturbs another's.
 */
public class RemoteDiskPressureTracker {

    private final Map<String, DiskPressureTracker> perMachine = new ConcurrentHashMap<>();

    /**
     * Record {@code aboveThreshold} for {@code machineName} and report whether it crossed a boundary
     * since the last observation for that machine.
     */
    public DiskPressureTracker.Transition update(String machineName, boolean aboveThreshold) {
        return perMachine.computeIfAbsent(machineName, m -> new DiskPressureTracker())
            .update(aboveThreshold);
    }
}
