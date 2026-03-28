package net.vaier.application.service;

import net.vaier.application.AddReverseProxyRouteUseCase;
import net.vaier.application.DeleteReverseProxyRouteUseCase;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.stereotype.Service;

@Service
public class ReverseProxyService implements AddReverseProxyRouteUseCase, DeleteReverseProxyRouteUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    public ReverseProxyService(ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes) {
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
    }

    @Override
    public void addReverseProxyRoute(ReverseProxyRouteUco route) {
        forPersistingReverseProxyRoutes.addReverseProxyRoute(
            route.dnsName(),
            route.address(),
            route.port(),
            route.requiresAuth(),
            null
        );
    }

    @Override
    public void deleteReverseProxyRoute(String dnsName) {
        forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(dnsName);
    }
}
