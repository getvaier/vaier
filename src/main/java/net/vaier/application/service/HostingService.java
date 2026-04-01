package net.vaier.application.service;

import net.vaier.application.DeleteHostedServiceUseCase;
import net.vaier.config.ServiceNames;
import net.vaier.application.GetHostedServicesUseCase;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsState;
import net.vaier.domain.DockerService;
import net.vaier.domain.Server;
import net.vaier.domain.Server.State;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.ReverseProxyRoute;
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
        List<ReverseProxyRoute> routes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        if (routes.isEmpty()) return List.of();

        // Fetch all expensive data once instead of per-service
        List<DnsRecord> allDnsRecords = forPersistingDnsRecords.getDnsZones().stream()
            .flatMap(zone -> forPersistingDnsRecords.getDnsRecords(zone).stream())
            .toList();
        List<VpnClient> vpnClients = forGettingVpnClients.getClients();
        List<DockerService> localServices = forGettingServerInfo.getServicesWithExposedPorts(Server.local());

        return routes.stream()
            .map(r -> toUco(r, allDnsRecords, vpnClients, localServices))
            .toList();
    }

    private HostedServiceUco toUco(ReverseProxyRoute route, List<DnsRecord> allDnsRecords,
                                    List<VpnClient> vpnClients, List<DockerService> localServices) {
        boolean mandatory = DeleteHostedServiceUseCase.MANDATORY_SUBDOMAINS.stream()
            .anyMatch(sub -> route.getDomainName().startsWith(sub + "."));
        return new HostedServiceUco(
            route.getName(),
            route.getDomainName(),
            dnsState(route.getDomainName(), allDnsRecords),
            route.getAddress(),
            route.getPort(),
            hostState(route.getAddress(), route.getPort(), localServices, vpnClients),
            route.getAuthInfo() != null,
            mandatory
        );
    }

    private DnsState dnsState(String dnsAddress, List<DnsRecord> allDnsRecords) {
        boolean found = allDnsRecords.stream()
            .filter(r -> r.name().equals(dnsAddress))
            .anyMatch(r -> r.type() == DnsRecordType.CNAME || r.type() == DnsRecordType.A);
        return found ? DnsState.OK : DnsState.NON_EXISTING;
    }

    private State hostState(String hostAddress, int hostPort, List<DockerService> localServices,
                             List<VpnClient> vpnClients) {
        if (localServices.stream().anyMatch(s -> s.listensOnPort(hostPort))) return State.OK;
        if (vpnClients.stream().anyMatch(p -> p.allowedIps() != null && p.allowedIps().startsWith(hostAddress) && isPeerConnected(p))) return State.OK;
        return State.UNREACHABLE;
    }

    private boolean isPeerConnected(VpnClient peer) {
        try {
            long handshake = Long.parseLong(peer.latestHandshake());
            long now = System.currentTimeMillis() / 1000;
            return handshake > 0 && (now - handshake) < 180;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
