package net.vaier.application;

import net.vaier.domain.MachineType;

public interface CreatePeerUseCase {

    CreatedPeerUco createPeer(String peerName);
    CreatedPeerUco createPeer(String peerName, MachineType peerType, String lanCidr);
    CreatedPeerUco createPeer(String peerName, MachineType peerType, String lanCidr, String lanAddress);

    record CreatedPeerUco(
        String name,
        String ipAddress,
        String publicKey,
        String privateKey,
        String clientConfigFile,
        MachineType peerType
    ) {}
}
