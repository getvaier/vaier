package net.vaier.application;

public interface ToggleServiceLaunchpadVisibilityUseCase {
    /** {@code pathPrefix} may be null for the host-only route on this FQDN, or a normalised path
     * (e.g. {@code "/auth"}) to target one of several sibling routes sharing the same host. */
    void setHiddenFromLaunchpad(String dnsName, String pathPrefix, boolean hiddenFromLaunchpad);

    /** Convenience overload for the common host-only case. */
    default void setHiddenFromLaunchpad(String dnsName, boolean hiddenFromLaunchpad) {
        setHiddenFromLaunchpad(dnsName, null, hiddenFromLaunchpad);
    }
}
