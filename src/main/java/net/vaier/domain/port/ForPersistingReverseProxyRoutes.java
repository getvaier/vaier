package net.vaier.domain.port;

import net.vaier.domain.ReverseProxyRoute;
import java.util.List;

public interface ForPersistingReverseProxyRoutes {
    void addReverseProxyRoute(String dnsName, String address, int port, boolean requiresAuth, String rootRedirectPath);
    void addLanReverseProxyRoute(String dnsName, String host, int port, String protocol,
                                 boolean requiresAuth, boolean directUrlDisabled);
    List<ReverseProxyRoute> getReverseProxyRoutes();
    void updateReverseProxyRoute(String routeName, ReverseProxyRoute updatedRoute);
    void deleteReverseProxyRoute(String routeName);
    void deleteReverseProxyRouteByDnsName(String dnsName);
    void setRouteAuthentication(String dnsName, boolean requiresAuth);
    void setRouteRootRedirectPath(String dnsName, String rootRedirectPath);
    void setRouteDirectUrlDisabled(String dnsName, boolean directUrlDisabled);
}
