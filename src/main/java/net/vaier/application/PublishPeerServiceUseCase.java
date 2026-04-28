package net.vaier.application;

import java.util.List;

public interface PublishPeerServiceUseCase {

    void publishService(String address, int port, String subdomain, boolean requiresAuth, String rootRedirectPath, boolean directUrlDisabled);

    PublishStatus getPublishStatus(String subdomain);

    List<PendingPublication> getPendingPublications();

    enum PublishableSource { LOCAL, PEER, LAN_DOCKER_HOST }

    record PublishableService(
        PublishableSource source,
        String peerName,
        String address,
        String containerName,
        int port,
        String rootRedirectPath,
        boolean ignored
    ) {}

    record PublishStatus(boolean dnsPropagated, boolean traefikActive) {}

    record PendingPublication(String subdomain, boolean requiresAuth, boolean dnsPropagated) {}
}
