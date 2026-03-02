package net.vaier.application;

public interface CreatePeerUseCase {

    CreatedPeerUco createPeer(String interfaceName, String peerName);
    CreatedPeerUco createPeer(String interfaceName, String peerName, boolean routeAllTraffic);

    record CreatedPeerUco(
        String name,
        String ipAddress,
        String publicKey,
        String privateKey,
        String clientConfigFile
    ) {}
}
