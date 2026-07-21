package net.vaier.domain.port;

import net.vaier.domain.DiscoveredLanMachine;

import java.util.Optional;

/**
 * Driven query port for reading the current LAN-scan snapshot from another domain — specifically so
 * the LAN-server domain can look up the discovered host it is about to adopt without depending on the
 * scanner's inbound use case. The read-only counterpart of {@link ForForgettingDiscoveredLanMachines}.
 */
public interface ForGettingDiscoveredLanMachines {

    /**
     * The discovered machine in the current snapshot with this {@code ipAddress}, or empty when none
     * matches (the snapshot is stale, the host was never found, or it was already forgotten). When the
     * same address appears on more than one relay LAN, the first match wins.
     */
    Optional<DiscoveredLanMachine> findByIpAddress(String ipAddress);
}
