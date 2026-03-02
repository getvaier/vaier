package net.vaier.application.service;

import net.vaier.application.GetHostedServicesUseCase;
import net.vaier.domain.HostedService;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class HostingService implements GetHostedServicesUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForGettingServerInfo forGettingServerInfo;
    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForGettingVpnClients forGettingVpnClients;

    public HostingService(ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes,
        ForGettingServerInfo forGettingServerInfo,
        ForPersistingDnsRecords forPersistingDnsRecords,
        ForGettingVpnClients forGettingVpnClients
    ) {
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
        this.forGettingServerInfo = forGettingServerInfo;
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.forGettingVpnClients = forGettingVpnClients;
    }

    @Override
    public List<HostedServiceUco> getHostedServices() {
        return forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
            .map(r -> new HostedService(
                r.getName(),
                r.getDomainName(),
                r.getAddress(),
                r.getPort(),
                r.getAuthInfo() != null,
                forPersistingDnsRecords,
                forGettingServerInfo,
                forGettingVpnClients
                )
            )
            .map(this::toUco)
            .toList();
    }

    private HostedServiceUco toUco(HostedService route) {
        return new HostedServiceUco(
            route.getName(),
            route.getDnsAddress(),
            route.dnsState(),
            route.getHostAddress(),
            route.getHostPort(),
            route.hostState(),
            route.isAuthenticated()
        );
    }
}
