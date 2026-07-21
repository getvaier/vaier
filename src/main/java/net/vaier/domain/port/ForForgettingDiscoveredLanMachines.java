package net.vaier.domain.port;

/**
 * Driven port for dropping a discovered host from the current LAN-scan snapshot — the side-effect of
 * adopting it as a registered LAN server, triggered from the LAN-server domain so the adopted host
 * stops surfacing as a candidate immediately (rather than waiting for the next sweep to filter it out
 * as already-registered). The write-side counterpart of {@link ForGettingDiscoveredLanMachines}.
 */
public interface ForForgettingDiscoveredLanMachines {

    /** Remove any discovered machine with this {@code ipAddress} from the current snapshot. */
    void forget(String ipAddress);
}
