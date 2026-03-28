package net.vaier.application;

public interface PublishPeerServiceUseCase {

    void publishService(String address, int port, String subdomain, boolean requiresAuth, String rootRedirectPath);

    enum PublishableSource { LOCAL, PEER }

    record PublishableService(
        PublishableSource source,
        String peerName,
        String address,
        String containerName,
        int port,
        String rootRedirectPath
    ) {}
}
