package net.vaier.application;

public interface PublishLanServiceUseCase {

    /**
     * Publish a Traefik route for a LAN service reachable via a relay peer (no Docker container required).
     * Resolves {@code machineName} to a registered {@link net.vaier.domain.LanServer} and uses its
     * {@code lanAddress} as the backend host; throws {@link IllegalArgumentException} when no LAN
     * server with that name exists. The resolved host must fall inside some relay peer's
     * {@code lanCidr} (or the Vaier server's own LAN CIDR), otherwise also throws.
     * {@code rootRedirectPath} may be null; when non-null, Traefik will redirect requests to the
     * service root (`/`) to {@code https://<fqdn><rootRedirectPath>}. {@code pathPrefix} is optional;
     * when set, the route only matches that path on the host so multiple LAN services can share
     * a single FQDN.
     */
    void publishLanService(String subdomain, String machineName, int port, String protocol,
                           boolean requiresAuth, boolean directUrlDisabled, String rootRedirectPath,
                           String pathPrefix);

    /** Convenience overload for the common no-path-prefix case. */
    default void publishLanService(String subdomain, String machineName, int port, String protocol,
                                   boolean requiresAuth, boolean directUrlDisabled, String rootRedirectPath) {
        publishLanService(subdomain, machineName, port, protocol, requiresAuth, directUrlDisabled, rootRedirectPath, null);
    }
}
