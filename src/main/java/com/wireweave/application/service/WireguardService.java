package com.wireweave.application.service;

import com.wireweave.application.GetWireguardConfigUseCase;
import com.wireweave.domain.WireguardConfig;
import com.wireweave.domain.port.ForManagingWireguardConfig;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WireguardService implements GetWireguardConfigUseCase {

    private final ForManagingWireguardConfig forManagingWireguardConfig;

    public WireguardService(ForManagingWireguardConfig forManagingWireguardConfig) {
        this.forManagingWireguardConfig = forManagingWireguardConfig;
    }

    @Override
    public WireguardConfigUco getConfig(String interfaceName) {
        WireguardConfig config = forManagingWireguardConfig.getConfig(interfaceName);
        return toUco(config);
    }

    @Override
    public List<WireguardPeerUco> getPeers(String interfaceName) {
        return forManagingWireguardConfig.getPeers(interfaceName).stream()
                .map(this::toUco)
                .toList();
    }

    private WireguardConfigUco toUco(WireguardConfig config) {
        return new WireguardConfigUco(
                toUco(config.getInterfaceConfig()),
                config.getPeers().stream().map(this::toUco).toList()
        );
    }

    private WireguardInterfaceUco toUco(WireguardConfig.WireguardInterface iface) {
        return new WireguardInterfaceUco(
                iface.getAddress(),
                iface.getListenPort(),
                iface.getPrivateKeyPath(),
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
