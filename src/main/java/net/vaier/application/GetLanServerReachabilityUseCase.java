package net.vaier.application;

import net.vaier.domain.Reachability;

public interface GetLanServerReachabilityUseCase {

    /**
     * Look up the latest cached "is the host on the network" status for the LAN server at the
     * given {@code lanAddress}. Returns {@link Reachability#UNKNOWN} if no probe has run yet.
     * Keyed by address (not name) so the status survives a LAN server rename.
     */
    Reachability getReachability(String lanAddress);

    /**
     * Epoch-second timestamp of the most recent successful probe (CONNECTED or REFUSED) for
     * the LAN server at {@code lanAddress}, or {@code null} if it has never responded since
     * startup. The value is preserved across subsequent unreachable probes — the UI uses it
     * for the same "Last seen 5m ago" affordance VPN peers get from their WireGuard handshake.
     */
    Long getLastSeenEpochSec(String lanAddress);

    /**
     * Force a fresh probe of every registered LAN server now (skipping the schedule).
     * Cache is updated synchronously.
     */
    void refreshAll();
}
