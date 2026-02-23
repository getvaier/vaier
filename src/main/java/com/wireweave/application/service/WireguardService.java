package com.wireweave.application.service;

import com.wireweave.application.CreatePeerUseCase;
import com.wireweave.application.GetWireguardConfigUseCase;
import com.wireweave.adapter.driven.WireguardAdapter;
import com.wireweave.domain.WireguardConfig;
import com.wireweave.domain.port.ForManagingWireguardConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class WireguardService implements GetWireguardConfigUseCase, CreatePeerUseCase {

    private final ForManagingWireguardConfig forManagingWireguardConfig;
    private final WireguardAdapter wireguardAdapter;

    @Value("${wireguard.config.path:c:/tmp/wireguard}")
    private String configBasePath;

    @Value("${wireguard.server.endpoint:}")
    private String serverEndpoint;

    @Value("${wireguard.server.public-key:}")
    private String serverPublicKey;

    @Value("${wireguard.defaults.persistent-keepalive:25}")
    private int defaultPersistentKeepalive;

    @Value("${wireguard.defaults.allowed-ips:0.0.0.0/0, ::/0}")
    private String defaultAllowedIPs;

    public WireguardService(ForManagingWireguardConfig forManagingWireguardConfig, WireguardAdapter wireguardAdapter) {
        this.forManagingWireguardConfig = forManagingWireguardConfig;
        this.wireguardAdapter = wireguardAdapter;
    }

    @Override
    public WireguardConfigUco getConfig(String interfaceName) {
        WireguardConfig config = forManagingWireguardConfig.getConfig(interfaceName);
        String serverPublicKey = forManagingWireguardConfig.getServerPublicKey(interfaceName);
        return toUco(config, serverPublicKey);
    }

    @Override
    public List<WireguardPeerUco> getPeers(String interfaceName) {
        return forManagingWireguardConfig.getPeers(interfaceName).stream()
                .map(this::toUco)
                .toList();
    }

    @Override
    public CreatedPeerUco createPeer(String interfaceName, String peerName) {
        // Generate key pair for the new peer
        WireguardAdapter.WireguardKeyPair keyPair = wireguardAdapter.generateKeyPair();

        // Get current config to determine next available IP
        WireguardConfig config = forManagingWireguardConfig.getConfig(interfaceName);
        String nextAvailableIp = allocateNextIpAddress(config);

        // Create peer with sensible defaults
        WireguardConfig.WireguardPeer newPeer = new WireguardConfig.WireguardPeer(
                peerName,
                keyPair.publicKey(),
                nextAvailableIp + "/32",  // Single IP for peer
                null,  // No endpoint for client peers
                null   // No keepalive for client peers
        );

        // Add peer to server config
        forManagingWireguardConfig.createPeer(interfaceName, newPeer);

        // Generate and save client config file
        String clientConfig = generateClientConfigFile(
                peerName,
                nextAvailableIp,
                keyPair.privateKey(),
                config.getInterfaceConfig()
        );
        String clientConfigPath = saveClientConfigFile(peerName, clientConfig);

        return new CreatedPeerUco(
                peerName,
                nextAvailableIp,
                keyPair.publicKey(),
                keyPair.privateKey(),
                clientConfigPath
        );
    }

    /**
     * Allocate next available IP address from the server's subnet.
     * Assumes server interface uses x.x.x.1 and peers start from x.x.x.2
     */
    private String allocateNextIpAddress(WireguardConfig config) {
        String serverAddress = config.getInterfaceConfig().getAddress();
        if (serverAddress == null) {
            throw new IllegalStateException("Server interface has no address configured");
        }

        // Extract base IP (e.g., "10.0.0.1/24" -> "10.0.0")
        String baseIp = serverAddress.split("/")[0];
        String[] octets = baseIp.split("\\.");
        String subnet = octets[0] + "." + octets[1] + "." + octets[2];

        // Collect all used IPs
        Set<Integer> usedIps = new HashSet<>();
        usedIps.add(Integer.parseInt(octets[3])); // Server IP

        for (WireguardConfig.WireguardPeer peer : config.getPeers()) {
            if (peer.getAllowedIPs() != null) {
                String peerIp = peer.getAllowedIPs().split("/")[0];
                String[] peerOctets = peerIp.split("\\.");
                if (peerOctets.length == 4 && peerOctets[0].equals(octets[0]) 
                    && peerOctets[1].equals(octets[1]) && peerOctets[2].equals(octets[2])) {
                    usedIps.add(Integer.parseInt(peerOctets[3]));
                }
            }
        }

        // Find next available IP (starting from .2)
        for (int i = 2; i < 255; i++) {
            if (!usedIps.contains(i)) {
                return subnet + "." + i;
            }
        }

        throw new IllegalStateException("No available IP addresses in subnet " + subnet + ".0/24");
    }

    /**
     * Generate client configuration file content.
     */
    private String generateClientConfigFile(String peerName, String ipAddress, 
                                           String privateKey, WireguardConfig.WireguardInterface serverInterface) {
        StringBuilder config = new StringBuilder();
        
        config.append("# WireGuard Client Configuration for: ").append(peerName).append("\n\n");
        config.append("[Interface]\n");
        config.append("PrivateKey = ").append(privateKey).append("\n");
        config.append("Address = ").append(ipAddress).append("/32\n");
        config.append("DNS = 1.1.1.1, 1.0.0.1\n\n");
        
        config.append("[Peer]\n");
        config.append("# Server\n");
        config.append("PublicKey = ").append(serverPublicKey).append("\n");
        config.append("AllowedIPs = ").append(defaultAllowedIPs).append("\n");
        config.append("Endpoint = ").append(serverEndpoint);
        if (!serverEndpoint.contains(":") && serverInterface.getListenPort() != null) {
            config.append(":").append(serverInterface.getListenPort());
        }
        config.append("\n");
        config.append("PersistentKeepalive = ").append(defaultPersistentKeepalive).append("\n");
        
        return config.toString();
    }

    /**
     * Save client configuration file to the clients folder.
     * Returns the absolute path to the saved file.
     */
    private String saveClientConfigFile(String peerName, String clientConfig) {
        try {
            // Create clients directory if it doesn't exist
            Path clientsDir = Paths.get(configBasePath, "clients");
            if (!Files.exists(clientsDir)) {
                Files.createDirectories(clientsDir);
            }

            // Sanitize peer name for filename (replace spaces and special chars with underscores)
            String sanitizedName = peerName.replaceAll("[^a-zA-Z0-9-_]", "_");
            String fileName = sanitizedName + ".conf";
            Path clientConfigPath = clientsDir.resolve(fileName);

            // Write config file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(clientConfigPath.toFile()))) {
                writer.write(clientConfig);
            }

            return clientConfigPath.toAbsolutePath().toString();

        } catch (IOException e) {
            throw new RuntimeException("Failed to save client config file for peer: " + peerName, e);
        }
    }

    private WireguardConfigUco toUco(WireguardConfig config, String serverPublicKey) {
        return new WireguardConfigUco(
                toUco(config.getInterfaceConfig(), serverPublicKey),
                config.getPeers().stream().map(this::toUco).toList()
        );
    }

    private WireguardInterfaceUco toUco(WireguardConfig.WireguardInterface iface, String serverPublicKey) {
        return new WireguardInterfaceUco(
                iface.getAddress(),
                iface.getListenPort(),
                iface.getPrivateKeyPath(),
                serverPublicKey,
                iface.getPostUpCommands(),
                iface.getPostDownCommands()
        );
    }

    private WireguardPeerUco toUco(WireguardConfig.WireguardPeer peer) {
        return new WireguardPeerUco(
                peer.getName(),
                peer.getPublicKey(),
                peer.getAllowedIPs(),
                peer.getEndpoint(),
                peer.getPersistentKeepalive()
        );
    }
}
