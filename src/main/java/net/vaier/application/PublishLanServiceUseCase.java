package net.vaier.application;

public interface PublishLanServiceUseCase {

    /**
     * Publish a Traefik route for a LAN service reachable via a relay peer (no Docker container required).
     * Validates that {@code host} falls inside some relay peer's {@code lanCidr}; throws
     * {@link IllegalArgumentException} if not. {@code rootRedirectPath} may be null; when non-null,
     * Traefik will redirect requests to the service root (`/`) to {@code https://<fqdn><rootRedirectPath>}.
     * {@code pathPrefix} is optional; when set, the route only matches that path on the host so
     * multiple LAN services can share a single FQDN.
     */
    void publishLanService(String subdomain, String host, int port, String protocol,
                           boolean requiresAuth, boolean directUrlDisabled, String rootRedirectPath,
                           String pathPrefix);

    /** Convenience overload for the common host-only case. */
    default void publishLanService(String subdomain, String host, int port, String protocol,
                                   boolean requiresAuth, boolean directUrlDisabled, String rootRedirectPath) {
        publishLanService(subdomain, host, port, protocol, requiresAuth, directUrlDisabled, rootRedirectPath, null);
    }
}
