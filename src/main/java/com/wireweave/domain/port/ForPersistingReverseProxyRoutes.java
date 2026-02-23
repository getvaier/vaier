package com.wireweave.domain.port;

import com.wireweave.domain.ReverseProxyRoute;
import java.util.List;

public interface ForPersistingReverseProxyRoutes {
    void addReverseProxyRoute(ReverseProxyRoute route);
    List<ReverseProxyRoute> getReverseProxyRoutes();
    void updateReverseProxyRoute(String routeName, ReverseProxyRoute updatedRoute);
    void deleteReverseProxyRoute(String routeName);
}
