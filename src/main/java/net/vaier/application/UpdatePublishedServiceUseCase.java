package net.vaier.application;

public interface UpdatePublishedServiceUseCase {
    /**
     * Applies a partial update to the route at {@code dnsName} + {@code pathPrefix}. Each field in
     * {@code patch} that is non-null is applied; null fields are left unchanged. For string fields
     * an empty string clears the value (matching the per-field endpoints' prior behaviour).
     * {@code pathPrefix} may be null for the host-only route on this FQDN, or a normalised path
     * (e.g. {@code "/auth"}) to target one of several sibling routes sharing the same host.
     */
    void updateService(String dnsName, String pathPrefix, PublishedServicePatch patch);

    /**
     * Partial update to a published service. Any field set to {@code null} means "leave unchanged";
     * for the string fields, an empty string means "clear" (consistent with how the prior per-field
     * endpoints treated blank input).
     */
    record PublishedServicePatch(
        Boolean requiresAuth,
        Boolean directUrlDisabled,
        Boolean hiddenFromLaunchpad,
        String rootRedirectPath,
        String launchpadAlias,
        String versionEndpoint,
        String versionProperty,
        /**
         * The route's auth mode wire value ({@code none}/{@code authelia}/{@code social}); null means
         * "leave unchanged". Supersedes the legacy {@code requiresAuth} toggle — when both are set,
         * {@code authMode} wins.
         */
        String authMode
    ) {
        /** Back-compat constructor for callers predating the {@code authMode} field. */
        public PublishedServicePatch(Boolean requiresAuth, Boolean directUrlDisabled,
                                     Boolean hiddenFromLaunchpad, String rootRedirectPath,
                                     String launchpadAlias, String versionEndpoint, String versionProperty) {
            this(requiresAuth, directUrlDisabled, hiddenFromLaunchpad, rootRedirectPath,
                launchpadAlias, versionEndpoint, versionProperty, null);
        }
    }
}
