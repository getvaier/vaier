package net.vaier.application.service;

import net.vaier.application.PublishingConstants;
import net.vaier.application.PublishedServicesCacheInvalidator;
import net.vaier.config.ConfigResolver;
import net.vaier.config.ServiceNames;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.GetLaunchpadServicesUseCase;
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
import net.vaier.domain.port.ForResolvingPeerNames;
import net.vaier.domain.ReverseProxyRoute;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PublishingService implements GetPublishedServicesUseCase, GetLaunchpadServicesUseCase, PublishedServicesCacheInvalidator {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForGettingServerInfo forGettingServerInfo;
    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForResolvingPeerNames forResolvingPeerNames;
    private final ConfigResolver configResolver;

    private volatile List<PublishedServiceUco> cache = null;

    public PublishingService(ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes,
        ForGettingServerInfo forGettingServerInfo,
        ForPersistingDnsRecords forPersistingDnsRecords,
        ForGettingVpnClients forGettingVpnClients,
        ForResolvingPeerNames forResolvingPeerNames,
        ConfigResolver configResolver
    ) {
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
        this.forGettingServerInfo = forGettingServerInfo;
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forResolvingPeerNames = forResolvingPeerNames;
        this.configResolver = configResolver;
    }

    @Override
    public void invalidatePublishedServicesCache() {
        cache = null;
    }

    @Override
    public List<PublishedServiceUco> getPublishedServices() {
        if (cache != null) return cache;

        List<ReverseProxyRoute> routes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        if (routes.isEmpty()) return List.of();

        // Fetch all expensive data once instead of per-service
        List<DnsRecord> allDnsRecords = forPersistingDnsRecords.getDnsZones().stream()
            .flatMap(zone -> forPersistingDnsRecords.getDnsRecords(zone).stream())
            .toList();
        List<VpnClient> vpnClients = forGettingVpnClients.getClients();
        List<DockerService> localServices = forGettingServerInfo.getServicesWithExposedPorts(Server.local());

        cache = routes.stream()
            .map(r -> toUco(r, allDnsRecords, vpnClients, localServices))
            .toList();
        return cache;
    }

    @Override
    public List<LaunchpadServiceUco> getLaunchpadServices() {
        return getPublishedServices().stream()
            .filter(s -> s.dnsState() == DnsState.OK)
            .map(s -> new LaunchpadServiceUco(s.dnsAddress(), s.hostAddress(), s.state()))
            .toList();
    }

    private PublishedServiceUco toUco(ReverseProxyRoute route, List<DnsRecord> allDnsRecords,
                                    List<VpnClient> vpnClients, List<DockerService> localServices) {
        boolean mandatory = PublishingConstants.MANDATORY_SUBDOMAINS.stream()
            .anyMatch(sub -> route.getDomainName().startsWith(sub + "."));
        return new PublishedServiceUco(
            displayName(route, localServices, vpnClients),
            route.getDomainName(),
            dnsState(route.getDomainName(), allDnsRecords),
            route.getAddress(),
            route.getPort(),
            hostState(route.getAddress(), route.getPort(), localServices, vpnClients),
            route.getAuthInfo() != null,
            mandatory,
            route.getRootRedirectPath()
        );
    }

    private String displayName(ReverseProxyRoute route, List<DockerService> localServices,
                               List<VpnClient> vpnClients) {
        String subdomain = extractSubdomain(route.getDomainName());
        String server = resolveServer(route.getAddress(), route.getPort(), localServices, vpnClients);
        // Strip server name from subdomain suffix: "openhab.colina27" + "colina27" → "openhab"
        if (!"local".equals(server) && subdomain.endsWith("." + server)) {
            subdomain = subdomain.substring(0, subdomain.length() - server.length() - 1);
        }
        return subdomain + " @ " + server;
    }

    private String extractSubdomain(String dnsAddress) {
        String domain = configResolver.getDomain();
        if (domain != null && dnsAddress.endsWith("." + domain)) {
            return dnsAddress.substring(0, dnsAddress.length() - domain.length() - 1);
        }
        return dnsAddress;
    }

    private String resolveServer(String address, int port, List<DockerService> localServices,
                                 List<VpnClient> vpnClients) {
        // Check VPN peers first — a peer IP is unambiguous, whereas port-only local
        // matching can produce false positives when a local container happens to use the same port.
        boolean isPeer = vpnClients.stream()
            .anyMatch(p -> p.allowedIps() != null && p.allowedIps().startsWith(address));
        if (isPeer) {
            String peerName = forResolvingPeerNames.resolvePeerNameByIp(address);
            return peerName.equals(address) ? address : peerName;
        }
        return "local";
    }



    private DnsState dnsState(String dnsAddress, List<DnsRecord> allDnsRecords) {
        boolean found = allDnsRecords.stream()
            .filter(r -> r.name().equals(dnsAddress))
            .anyMatch(r -> r.type() == DnsRecordType.CNAME || r.type() == DnsRecordType.A);
        return found ? DnsState.OK : DnsState.NON_EXISTING;
    }

    private State hostState(String hostAddress, int hostPort, List<DockerService> localServices,
                             List<VpnClient> vpnClients) {
        if (localServices.stream().anyMatch(s -> s.isRunning() && s.listensOnPort(hostPort))) return State.OK;
        if (vpnClients.stream().anyMatch(p -> p.allowedIps() != null && p.allowedIps().startsWith(hostAddress) && isPeerConnected(p))) return State.OK;
        return State.UNREACHABLE;
    }

    private boolean isPeerConnected(VpnClient peer) {
        try {
            long handshake = Long.parseLong(peer.latestHandshake());
            long now = System.currentTimeMillis() / 1000;
            return handshake > 0 && (now - handshake) < 300;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
