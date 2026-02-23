package com.wireweave.domain.port;

import com.wireweave.domain.WireguardConfig;

import java.util.List;

public interface ForManagingWireguardConfig {
    /**
     * Read a WireGuard configuration.
     *
     * @param interfaceName Name of the WireGuard interface (e.g., "wg0")
     * @return WireguardConfig object containing interface and peer information
     */
    WireguardConfig getConfig(String interfaceName);

    /**
     * Create a new peer in the configuration.
     *
     * @param interfaceName Name of the WireGuard interface
     * @param peer Peer to add
     */
    void createPeer(String interfaceName, WireguardConfig.WireguardPeer peer);

    /**
     * Update an existing peer in the configuration.
     *
     * @param interfaceName Name of the WireGuard interface
     * @param publicKey Public key of the peer to update
     * @param updatedPeer Updated peer information
     */
    void updatePeer(String interfaceName, String publicKey, WireguardConfig.WireguardPeer updatedPeer);

    /**
     * Delete a peer from the configuration.
     *
     * @param interfaceName Name of the WireGuard interface
     * @param publicKey Public key of the peer to delete
     */
    void deletePeer(String interfaceName, String publicKey);

    /**
     * Get all peers for an interface.
     *
     * @param interfaceName Name of the WireGuard interface
     * @return List of peers
     */
    List<WireguardConfig.WireguardPeer> getPeers(String interfaceName);

    /**
     * Apply the configuration to the running WireGuard interface.
     * This reloads the config file and applies changes.
     *
     * @param interfaceName Name of the WireGuard interface
     */
    void applyConfig(String interfaceName);

    /**
     * Get the server's public key for the specified interface.
     *
     * @param interfaceName Name of the WireGuard interface
     * @return The server's public key
     */
    String getServerPublicKey(String interfaceName);
}
