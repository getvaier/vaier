package com.wireweave.application;

public interface CreatePeerUseCase {

    CreatedPeerUco createPeer(String interfaceName, String peerName);

    record CreatedPeerUco(
        String name,
        String ipAddress,
        String publicKey,
        String privateKey,
        String clientConfigFile
    ) {}
}
