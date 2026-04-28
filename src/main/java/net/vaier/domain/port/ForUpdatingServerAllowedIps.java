package net.vaier.domain.port;

public interface ForUpdatingServerAllowedIps {

    /**
     * Updates the server-side {@code [Peer]} {@code AllowedIPs} entry for the peer at
     * the given VPN IP, then persists the change so it survives container restarts.
     * Hot — does not disconnect existing tunnels for unrelated peers.
     *
     * @param peerIpAddress the peer's VPN IP (e.g. {@code 10.13.13.6})
     * @param allowedIps    the new comma-separated AllowedIPs value
     *                      (e.g. {@code 10.13.13.6/32, 192.168.3.0/24})
     */
    void setPeerAllowedIps(String peerIpAddress, String allowedIps);
}
