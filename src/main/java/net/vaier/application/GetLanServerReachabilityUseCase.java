package net.vaier.application;

public interface GetLanServerReachabilityUseCase {

    /**
     * Look up the latest cached "is the host on the network" status for the named LAN server.
     * Returns {@link Reachability#UNKNOWN} if no probe has run yet.
     */
    Reachability getReachability(String lanServerName);

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
