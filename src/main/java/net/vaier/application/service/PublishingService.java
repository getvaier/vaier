package net.vaier.application.service;

import net.vaier.application.PublishingConstants;
import net.vaier.application.PublishedServicesCacheInvalidator;
import net.vaier.config.ConfigResolver;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.GetLaunchpadServicesUseCase;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsState;
import net.vaier.domain.DockerService;
import net.vaier.domain.Server;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
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
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ConfigResolver configResolver;

    private volatile List<PublishedServiceUco> cache = null;

    public PublishingService(ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes,
        ForGettingServerInfo forGettingServerInfo,
        ForPersistingDnsRecords forPersistingDnsRecords,
        ForGettingVpnClients forGettingVpnClients,
        ForResolvingPeerNames forResolvingPeerNames,
        ForGettingPeerConfigurations forGettingPeerConfigurations,
        ConfigResolver configResolver
    ) {
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
        this.forGettingServerInfo = forGettingServerInfo;
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forResolvingPeerNames = forResolvingPeerNames;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
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
    public List<LaunchpadServiceUco> getLaunchpadServices(String callerIp) {
        List<PeerConfiguration> peers = forGettingPeerConfigurations.getAllPeerConfigs();
        List<VpnClient> vpnClients = forGettingVpnClients.getClients();
        List<ReverseProxyRoute> routes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        return getPublishedServices().stream()
            .filter(s -> s.dnsState() == DnsState.OK)
            .map(s -> {
                ReverseProxyRoute route = routes.stream()
                    .filter(r -> r.getDomainName().equals(s.dnsAddress()))
                    .findFirst().orElse(null);
                String directUrl = route == null ? null : route.directUrl(callerIp, peers, vpnClients);
                return new LaunchpadServiceUco(s.dnsAddress(), s.hostAddress(), s.state(), directUrl);
            })
            .toList();
    }

    private PublishedServiceUco toUco(ReverseProxyRoute route, List<DnsRecord> allDnsRecords,
                                    List<VpnClient> vpnClients, List<DockerService> localServices) {
        boolean mandatory = PublishingConstants.MANDATORY_SUBDOMAINS.stream()
            .anyMatch(sub -> route.getDomainName().startsWith(sub + "."));
        return new PublishedServiceUco(
            route.displayName(configResolver.getDomain(), localServices, vpnClients, forResolvingPeerNames),
            route.getDomainName(),
            route.dnsState(allDnsRecords),
            route.getAddress(),
            route.getPort(),
            route.hostState(localServices, vpnClients),
            route.getAuthInfo() != null,
            mandatory,
            route.getRootRedirectPath(),
            route.isDirectUrlDisabled()
        );
    }
}
