package com.wireweave.application;

import java.util.List;

public interface GetWireguardConfigUseCase {

    WireguardConfigUco getConfig(String interfaceName);
    List<WireguardPeerUco> getPeers(String interfaceName);

    record WireguardConfigUco(
        WireguardInterfaceUco interfaceConfig,
        List<WireguardPeerUco> peers
    ) {}

    record WireguardInterfaceUco(
        String address,
        Integer listenPort,
        String privateKeyPath,
        String publicKey,
        List<String> postUpCommands,
        List<String> postDownCommands
    ) {}

    record WireguardPeerUco(
        String name,
        String publicKey,
        String allowedIPs,
        String endpoint,
        Integer persistentKeepalive
    ) {}
}
