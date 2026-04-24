package net.vaier.application.service;

import net.vaier.application.AddReverseProxyRouteUseCase;
import net.vaier.application.DeleteReverseProxyRouteUseCase;
import net.vaier.application.GetReverseProxyRoutesUseCase;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReverseProxyService implements
    AddReverseProxyRouteUseCase,
    DeleteReverseProxyRouteUseCase,
    GetReverseProxyRoutesUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    public ReverseProxyService(ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes) {
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
    }

    @Override
    public void addReverseProxyRoute(ReverseProxyRouteUco route) {
        ReverseProxyRoute.validateForPublication(route.dnsName(), route.address(), route.port());
        forPersistingReverseProxyRoutes.addReverseProxyRoute(
            route.dnsName(),
            route.address(),
            route.port(),
            route.requiresAuth(),
            route.rootRedirectPath()
        );
    }

    @Override
    public void deleteReverseProxyRoute(String dnsName) {
        ReverseProxyRoute.validateDnsName(dnsName);
        forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(dnsName);
    }

    @Override
    public List<ReverseProxyRoute> getReverseProxyRoutes() {
        return forPersistingReverseProxyRoutes.getReverseProxyRoutes();
    }
}
