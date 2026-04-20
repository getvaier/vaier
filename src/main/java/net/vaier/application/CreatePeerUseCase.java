package net.vaier.application;

import net.vaier.domain.PeerType;

public interface CreatePeerUseCase {

    CreatedPeerUco createPeer(String peerName);
    CreatedPeerUco createPeer(String peerName, PeerType peerType, String lanCidr);
    CreatedPeerUco createPeer(String peerName, PeerType peerType, String lanCidr, String lanAddress);

    record CreatedPeerUco(
        String name,
        String ipAddress,
        String publicKey,
        String privateKey,
        String clientConfigFile,
        PeerType peerType
    ) {}
}
