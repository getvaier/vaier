package net.vaier.application;

import java.util.List;

public interface PublishPeerServiceUseCase {

    /**
     * Publish a service. {@code pathPrefix} is optional; when null the route catches everything on
     * the host. When non-null (e.g. {@code "/auth"}) the route only matches that path scope,
     * letting multiple services coexist on one host. The first publish on a host creates the DNS
     * CNAME; subsequent publishes on the same host (regardless of pathPrefix) reuse it.
     */
    void publishService(String address, int port, String subdomain, boolean requiresAuth,
                        String rootRedirectPath, boolean directUrlDisabled, String pathPrefix);

    /** Convenience overload for the common host-only case. */
    default void publishService(String address, int port, String subdomain, boolean requiresAuth,
                                String rootRedirectPath, boolean directUrlDisabled) {
        publishService(address, port, subdomain, requiresAuth, rootRedirectPath, directUrlDisabled, null);
    }

    PublishStatus getPublishStatus(String subdomain);

    List<PendingPublication> getPendingPublications();

    enum PublishableSource { VAIER_SERVER, PEER, LAN_SERVER }

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
