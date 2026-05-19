package net.vaier.application;

public interface EditServiceLaunchpadAliasUseCase {
    /** {@code launchpadAlias} may be null or blank to clear, or a non-blank string to override the
     * default launchpad tile label for the route at {@code dnsName} + {@code pathPrefix}.
     * {@code pathPrefix} may be null for the host-only route on this FQDN, or a normalised path. */
    void setLaunchpadAlias(String dnsName, String pathPrefix, String launchpadAlias);

    /** Convenience overload for the common host-only case. */
    default void setLaunchpadAlias(String dnsName, String launchpadAlias) {
        setLaunchpadAlias(dnsName, null, launchpadAlias);
    }
}
