package net.vaier.application;

public interface ToggleServiceDirectUrlDisabledUseCase {
    /** {@code pathPrefix} may be null for the host-only route on this FQDN, or a normalised path
     * (e.g. {@code "/auth"}) to target one of several sibling routes sharing the same host. */
    void setDirectUrlDisabled(String dnsName, String pathPrefix, boolean directUrlDisabled);

    /** Convenience overload for the common host-only case. */
    default void setDirectUrlDisabled(String dnsName, boolean directUrlDisabled) {
        setDirectUrlDisabled(dnsName, null, directUrlDisabled);
    }
}
