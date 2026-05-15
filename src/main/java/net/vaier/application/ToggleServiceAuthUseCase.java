package net.vaier.application;

public interface ToggleServiceAuthUseCase {
    /** {@code pathPrefix} may be null for the host-only route on this FQDN, or a normalised path
     * (e.g. {@code "/auth"}) to target one of several sibling routes sharing the same host. */
    void setAuthentication(String dnsName, String pathPrefix, boolean requiresAuth);

    /** Convenience overload for the common host-only case. */
    default void setAuthentication(String dnsName, boolean requiresAuth) {
        setAuthentication(dnsName, null, requiresAuth);
    }
}
