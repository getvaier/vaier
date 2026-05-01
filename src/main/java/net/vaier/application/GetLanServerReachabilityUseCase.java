package net.vaier.application;

public interface GetLanServerReachabilityUseCase {

    /**
     * Look up the latest cached "is the host on the network" status for the named LAN server.
     * Returns {@link Reachability#UNKNOWN} if no probe has run yet.
     */
    Reachability getReachability(String lanServerName);

    /**
     * Epoch-second timestamp of the most recent successful probe (CONNECTED or REFUSED) for
     * the named LAN server, or {@code null} if it has never responded since startup. The
     * value is preserved across subsequent unreachable probes — the UI uses it for the same
     * "Last seen 5m ago" affordance VPN peers get from their WireGuard handshake.
     */
    Long getLastSeenEpochSec(String lanServerName);

    /**
     * Force a fresh probe of every registered LAN server now (skipping the schedule).
     * Cache is updated synchronously.
     */
    void refreshAll();

    /**
     * Binary "is the host pingable" result. {@link #OK} means at least one probed port
     * either completed a TCP handshake or sent back a TCP RST — the host is on the network.
     * {@link #DOWN} means every probe timed out — likely powered off or unreachable.
     * {@link #UNKNOWN} means we have not yet probed this host.
     *
     * <p>For Docker-enabled LAN servers the UI combines this with the Docker scrape status:
     * pingable + Docker scrape OK = healthy (green); pingable + Docker scrape failed =
     * degraded (yellow); not pingable = down (red).
     */
    enum Reachability {
        OK,
        DOWN,
        UNKNOWN
    }
}
