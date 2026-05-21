package net.vaier.application;

public interface EditServiceVersionEndpointUseCase {
    /**
     * Sets — or, when either argument is null/blank, clears — the version endpoint for the route at
     * {@code dnsName} + {@code pathPrefix}. {@code versionEndpoint} is the URL (absolute, or a path
     * relative to the service) Vaier GETs; {@code versionProperty} is the {@code name="value"} label
     * it reads the running version from. {@code pathPrefix} may be null for the host-only route.
     */
    void setVersionEndpoint(String dnsName, String pathPrefix, String versionEndpoint,
                            String versionProperty);

    /** Convenience overload for the common host-only case. */
    default void setVersionEndpoint(String dnsName, String versionEndpoint, String versionProperty) {
        setVersionEndpoint(dnsName, null, versionEndpoint, versionProperty);
    }
}
