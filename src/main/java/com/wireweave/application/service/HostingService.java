package com.wireweave.application.service;

import com.wireweave.application.GetHostedServicesUseCase;
import com.wireweave.domain.HostedService;
import com.wireweave.domain.port.ForGettingDockerInfo;
import com.wireweave.domain.port.ForGettingReverseProxyRoutes;
import com.wireweave.domain.port.ForPersistingDnsRecords;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class HostingService implements GetHostedServicesUseCase {

    private final ForGettingReverseProxyRoutes forGettingReverseProxyRoutes;
    private final ForGettingDockerInfo forGettingDockerInfo;
    private final ForPersistingDnsRecords forPersistingDnsRecords;

    public HostingService(ForGettingReverseProxyRoutes forGettingReverseProxyRoutes,
        ForGettingDockerInfo forGettingDockerInfo, ForPersistingDnsRecords forPersistingDnsRecords) {
        this.forGettingReverseProxyRoutes = forGettingReverseProxyRoutes;
        this.forGettingDockerInfo = forGettingDockerInfo;
        this.forPersistingDnsRecords = forPersistingDnsRecords;
    }

    @Override
    public List<HostedServiceUco> getHostedServices() {
        return forGettingReverseProxyRoutes.getReverseProxyRoutes().stream()
            .map(r -> new HostedService(
                r.getName(),
                r.getDomainName(),
                r.getAddress(),
                r.getPort(),
                r.getAuthInfo() != null,
                forPersistingDnsRecords,
                forGettingDockerInfo
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
