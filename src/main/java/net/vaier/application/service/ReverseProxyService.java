package net.vaier.application.service;

import net.vaier.application.AddReverseProxyRouteUseCase;
import net.vaier.application.DeleteReverseProxyRouteUseCase;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.stereotype.Service;

@Service
public class ReverseProxyService implements AddReverseProxyRouteUseCase, DeleteReverseProxyRouteUseCase {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    public ReverseProxyService(ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes) {
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
    }

    @Override
    public void addReverseProxyRoute(ReverseProxyRouteUco route) {
        requireNonBlank(route.dnsName(), "dnsName");
        requireNonBlank(route.address(), "address");
        requireValidPort(route.port());
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
        requireNonBlank(dnsName, "dnsName");
        forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(dnsName);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireValidPort(int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(
                "port must be between " + MIN_PORT + " and " + MAX_PORT + " (was " + port + ")");
        }
    }
}
