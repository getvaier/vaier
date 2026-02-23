package com.wireweave.domain.port;

import com.wireweave.domain.ReverseProxyRoute;
import java.util.List;

public interface ForPersistingReverseProxyRoutes {
    void addReverseProxyRoute(String dnsName, String address, int port, boolean requiresAuth);
    List<ReverseProxyRoute> getReverseProxyRoutes();
    void updateReverseProxyRoute(String routeName, ReverseProxyRoute updatedRoute);
    void deleteReverseProxyRoute(String routeName);
    void deleteReverseProxyRouteByDnsName(String dnsName);
}
