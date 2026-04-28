package net.vaier.application;

public interface PublishLanServiceUseCase {

    /**
     * Publish a Traefik route for a LAN service reachable via a relay peer (no Docker container required).
     * Validates that {@code host} falls inside some relay peer's {@code lanCidr}; throws
     * {@link IllegalArgumentException} if not.
     */
    void publishLanService(String subdomain, String host, int port, String protocol,
                           boolean requiresAuth, boolean directUrlDisabled);
}
