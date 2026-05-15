package net.vaier.application;

public interface EditServiceRedirectUseCase {
    /** {@code pathPrefix} may be null for the host-only route on this FQDN, or a normalised path
     * (e.g. {@code "/auth"}) to target one of several sibling routes sharing the same host. */
    void setRootRedirectPath(String dnsName, String pathPrefix, String rootRedirectPath);

    /** Convenience overload for the common host-only case. */
    default void setRootRedirectPath(String dnsName, String rootRedirectPath) {
        setRootRedirectPath(dnsName, null, rootRedirectPath);
    }
}
