package net.vaier.adapter.driven;

import net.vaier.domain.DiscoveredLanMachine;
import net.vaier.domain.port.ForForgettingDiscoveredLanMachines;
import net.vaier.domain.port.ForGettingDiscoveredLanMachines;
import net.vaier.domain.port.ForStoringDiscoveredLanMachines;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * In-memory store for the LAN-scan snapshot. Owns the results list and the last-completed timestamp
 * that used to live as {@code volatile} fields on {@code LanScannerService} — a {@code *Service} must
 * not implement a driven ({@code For*}) port, so the state moved here. The scanner writes through
 * {@link ForStoringDiscoveredLanMachines}; the LAN-server domain reads/forgets a candidate through
 * {@link ForGettingDiscoveredLanMachines} / {@link ForForgettingDiscoveredLanMachines}.
 *
 * <p>Mirrors {@link InMemoryLanReachabilityCache}: {@code volatile} fields written from the scan
 * executor thread and read from request threads.
 */
@Component
public class InMemoryDiscoveredLanMachineStore implements
    ForGettingDiscoveredLanMachines, ForForgettingDiscoveredLanMachines, ForStoringDiscoveredLanMachines {

    private volatile List<DiscoveredLanMachine> results = List.of();
    private volatile Instant lastScanCompleted;

    @Override
    public List<DiscoveredLanMachine> current() {
        return results;
    }

    @Override
    public Instant lastScanCompleted() {
        return lastScanCompleted;
    }

    @Override
    public void store(List<DiscoveredLanMachine> results) {
        this.results = results;
        this.lastScanCompleted = Instant.now();
    }

    @Override
    public Optional<DiscoveredLanMachine> findByIpAddress(String ipAddress) {
        // Read-side of the adoption port: the LAN-server domain looks the candidate up here so it
        // never depends on the scanner's inbound use case. First match wins if an address somehow
        // appears on two relay LANs.
        return results.stream()
            .filter(m -> m.ipAddress().equals(ipAddress))
            .findFirst();
    }

    @Override
    public void forget(String ipAddress) {
        // Write-side of the adoption port: drop the adopted host from the snapshot so it stops
        // surfacing as a candidate immediately, rather than waiting for the next sweep to filter it
        // out as already-registered. Deliberately leaves the last-completed timestamp untouched.
        results = results.stream()
            .filter(m -> !m.ipAddress().equals(ipAddress))
            .toList();
    }
}
