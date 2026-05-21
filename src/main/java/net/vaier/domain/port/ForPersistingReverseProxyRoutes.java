package net.vaier.domain.port;

import net.vaier.domain.ReverseProxyRoute;
import java.util.List;

public interface ForPersistingReverseProxyRoutes {
    /**
     * Add a route. {@code pathPrefix} may be null for a host-only route, or a normalised path like
     * {@code "/auth"} to add a path-scoped route that coexists with others on the same host.
     * Router/service names include a path-derived slug so multiple routes on one host don't collide.
     */
    void addReverseProxyRoute(String dnsName, String address, int port, boolean requiresAuth,
                              String rootRedirectPath, String pathPrefix);
    void addLanReverseProxyRoute(String dnsName, String host, int port, String protocol,
                                 boolean requiresAuth, boolean directUrlDisabled, String rootRedirectPath,
                                 String pathPrefix);

    /** Convenience overload for the common host-only case. */
    default void addReverseProxyRoute(String dnsName, String address, int port, boolean requiresAuth,
                                      String rootRedirectPath) {
        addReverseProxyRoute(dnsName, address, port, requiresAuth, rootRedirectPath, null);
    }
    /** Convenience overload for the common host-only case. */
    default void addLanReverseProxyRoute(String dnsName, String host, int port, String protocol,
                                         boolean requiresAuth, boolean directUrlDisabled,
                                         String rootRedirectPath) {
        addLanReverseProxyRoute(dnsName, host, port, protocol, requiresAuth, directUrlDisabled,
            rootRedirectPath, null);
    }

    List<ReverseProxyRoute> getReverseProxyRoutes();
    void updateReverseProxyRoute(String routeName, ReverseProxyRoute updatedRoute);
    void deleteReverseProxyRoute(String routeName);
    void deleteReverseProxyRouteByDnsName(String dnsName);

    /** Path-aware setters target a specific (dnsName, pathPrefix) router. */
    void setRouteAuthentication(String dnsName, String pathPrefix, boolean requiresAuth);
    void setRouteRootRedirectPath(String dnsName, String pathPrefix, String rootRedirectPath);
    void setRouteDirectUrlDisabled(String dnsName, String pathPrefix, boolean directUrlDisabled);
    void setRouteHiddenFromLaunchpad(String dnsName, String pathPrefix, boolean hiddenFromLaunchpad);
    /** {@code launchpadAlias} may be null or blank to clear the override; non-blank to set it. */
    void setRouteLaunchpadAlias(String dnsName, String pathPrefix, String launchpadAlias);
    /** Sets (or, when either argument is null/blank, clears) the route's version endpoint — the
     *  URL path Vaier GETs and the property name it reads the running version from. */
    void setRouteVersionEndpoint(String dnsName, String pathPrefix, String versionEndpoint,
                                 String versionProperty);

    default void setRouteAuthentication(String dnsName, boolean requiresAuth) {
        setRouteAuthentication(dnsName, null, requiresAuth);
    }
    default void setRouteRootRedirectPath(String dnsName, String rootRedirectPath) {
        setRouteRootRedirectPath(dnsName, null, rootRedirectPath);
    }
    default void setRouteDirectUrlDisabled(String dnsName, boolean directUrlDisabled) {
        setRouteDirectUrlDisabled(dnsName, null, directUrlDisabled);
    }
    default void setRouteHiddenFromLaunchpad(String dnsName, boolean hiddenFromLaunchpad) {
        setRouteHiddenFromLaunchpad(dnsName, null, hiddenFromLaunchpad);
    }
    default void setRouteLaunchpadAlias(String dnsName, String launchpadAlias) {
        setRouteLaunchpadAlias(dnsName, null, launchpadAlias);
    }
    default void setRouteVersionEndpoint(String dnsName, String versionEndpoint, String versionProperty) {
        setRouteVersionEndpoint(dnsName, null, versionEndpoint, versionProperty);
    }
}
