package net.vaier.application;

import net.vaier.domain.LanServer;

/**
 * Adopt a {@link net.vaier.domain.DiscoveredLanMachine} the LAN scanner (issue #246) found into a
 * registered {@link LanServer} in one call — slice 1 of the consolidated "Add a machine" flow. Every
 * derivable field (name, LAN address, Docker settings, device category) comes from the discovered
 * host's domain-derived adoption profile; the operator only optionally overrides the name. The adopted
 * host is dropped from the scan snapshot so it stops surfacing as a candidate.
 */
public interface AdoptDiscoveredMachineUseCase {

    /**
     * Register the discovered host at {@code ipAddress} as a LAN server, using {@code nameOverride}
     * as its name when non-blank and the profile's suggested name otherwise, then forget it from the
     * snapshot. Returns the created LAN server.
     *
     * @throws net.vaier.domain.NotFoundException when no discovered machine at {@code ipAddress} is in
     *                                            the current snapshot
     */
    LanServer adopt(String ipAddress, String nameOverride);
}
