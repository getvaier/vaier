package net.vaier.application;

public interface UpdateLanCidrUseCase {

    /**
     * Sets, changes, or clears (when {@code lanCidr} is null/blank) the LAN CIDR a relay
     * peer is responsible for. Updates the server-side wg0 routing table so traffic for
     * the CIDR flows through this peer, and persists the value in the peer's metadata.
     *
     * @throws IllegalArgumentException if the peer does not exist
     * @throws IllegalStateException    if another peer already owns the CIDR
     */
    void updateLanCidr(String peerName, String lanCidr);
}
