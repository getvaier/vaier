package net.vaier.domain;

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
public enum Reachability {
    OK,
    DOWN,
    UNKNOWN
}
