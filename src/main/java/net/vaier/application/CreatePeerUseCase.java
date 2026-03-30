package net.vaier.application;

import net.vaier.domain.PeerType;

public interface CreatePeerUseCase {

    CreatedPeerUco createPeer(String interfaceName, String peerName);
    CreatedPeerUco createPeer(String interfaceName, String peerName, PeerType peerType, String lanCidr);
    CreatedPeerUco createPeer(String interfaceName, String peerName, PeerType peerType, String lanCidr, boolean usePiholeDns);

    record CreatedPeerUco(
        String name,
        String ipAddress,
        String publicKey,
        String privateKey,
        String clientConfigFile,
        PeerType peerType
    ) {}
}
