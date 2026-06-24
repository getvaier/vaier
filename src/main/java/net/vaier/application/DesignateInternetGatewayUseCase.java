package net.vaier.application;

import net.vaier.domain.PeerNotFoundException;

public interface DesignateInternetGatewayUseCase {

    /**
     * Designates {@code peerName} as Vaier's single internet gateway — the central internet egress
     * for full-tunnel VPN clients (#174). Adds {@code 0.0.0.0/0} to the peer's server-side
     * {@code AllowedIPs} (alongside its {@code /32} and any relay {@code lanCidr}) so the server
     * forwards client internet traffic to it, and records {@code gateway:true} in its metadata.
     * <p>
     * At most one peer is the gateway at a time: any previously-designated gateway is reverted to a
     * plain {@code /32} (plus its own {@code lanCidr}) first. Selecting an already-designated peer
     * is idempotent.
     *
     * @throws PeerNotFoundException if the peer does not exist
     * @throws IllegalArgumentException if the peer is not eligible (only Linux server peers that run
     *                                  the bash setup script — i.e. {@code UBUNTU_SERVER} — qualify)
     */
    void setInternetGateway(String peerName);

    /**
     * Clears the internet-gateway designation: reverts the currently-designated gateway (if any) to
     * a plain {@code /32} (plus its {@code lanCidr}) and removes its {@code gateway} metadata flag.
     * A no-op when no peer is currently designated.
     */
    void clearInternetGateway();
}
