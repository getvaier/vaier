package com.wireweave.application.service;

import com.wireweave.application.AddReverseProxyRouteUseCase;
import com.wireweave.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.stereotype.Service;

@Service
public class ReverseProxyService implements AddReverseProxyRouteUseCase {

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
            route.requiresAuth()
        );
    }
}
