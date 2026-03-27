package net.vaier.application;

public interface PublishPeerServiceUseCase {

    void publishService(String peerIp, int port, String subdomain, boolean requiresAuth);

    record PublishableService(
        String peerName,
        String peerIp,
        String containerName,
        int port
    ) {}
}
