package net.vaier.domain.port;

import net.vaier.domain.DiscoveredLanMachine;

import java.time.Instant;
import java.util.List;

/**
 * Driven port for the write side of the LAN-scan snapshot store, used by the scanner to publish its
 * results. The in-memory snapshot used to live as mutable fields on {@code LanScannerService}, but a
 * {@code *Service} must not own that state behind a driven ({@code For*}) read port — the store is
 * infrastructure. The read side is {@link ForGettingDiscoveredLanMachines} /
 * {@link ForForgettingDiscoveredLanMachines}; this is the write/owner side the scanner talks to.
 */
public interface ForStoringDiscoveredLanMachines {

    /** The current snapshot as last stored — the raw results, before any ignored-flag decoration. */
    List<DiscoveredLanMachine> current();

    /** When the last {@link #store} landed, or {@code null} before any scan has completed. */
    Instant lastScanCompleted();

    /** Replace the whole snapshot with {@code results} and stamp the completion time as now. */
    void store(List<DiscoveredLanMachine> results);
}
