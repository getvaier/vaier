package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.DiscoverLanDockerHostContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.EditServiceRedirectUseCase;
import net.vaier.application.GetLaunchpadServicesUseCase;
import net.vaier.application.GetLocalDockerServicesUseCase;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.IgnorePublishableServiceUseCase;
import net.vaier.application.PublishLanServiceUseCase;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.application.PublishedServicesCacheInvalidator;
import net.vaier.application.PublishingConstants;
import net.vaier.application.ToggleServiceAuthUseCase;
import net.vaier.application.ToggleServiceDirectUrlDisabledUseCase;
import net.vaier.application.UnignorePublishableServiceUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsState;
import net.vaier.domain.DnsZone;
import net.vaier.domain.Cidr;
import net.vaier.domain.DockerService;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Server;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForManagingIgnoredServices;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForResolvingDns;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class PublishingService implements
    GetPublishedServicesUseCase,
    GetLaunchpadServicesUseCase,
    PublishedServicesCacheInvalidator,
    PublishPeerServiceUseCase,
    PublishLanServiceUseCase,
    DeletePublishedServiceUseCase,
    GetPublishableServicesUseCase,
    ToggleServiceAuthUseCase,
    ToggleServiceDirectUrlDisabledUseCase,
    EditServiceRedirectUseCase,
    IgnorePublishableServiceUseCase,
    UnignorePublishableServiceUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForGettingServerInfo forGettingServerInfo;
    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForResolvingPeerNames forResolvingPeerNames;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ConfigResolver configResolver;
    private final ForPublishingEvents forPublishingEvents;
    private final ForResolvingDns forResolvingDns;
    private final ForManagingIgnoredServices forManagingIgnoredServices;
    private final PendingPublicationsService pendingPublicationsService;
    private final DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    private final DiscoverLanDockerHostContainersUseCase discoverLanDockerHostContainersUseCase;
    private final GetLocalDockerServicesUseCase getLocalDockerServicesUseCase;

    private volatile List<PublishedServiceUco> cache = null;

    long dnsTimeoutMillis = 120_000;
    long dnsRetryIntervalMillis = 3_000;
    long traefikActivationTimeoutMillis = 15_000;
    long traefikActivationRetryIntervalMillis = 500;

    private record PendingState(boolean requiresAuth, boolean dnsPropagated) {}

    private final Map<String, PendingState> pendingPublishes = new ConcurrentHashMap<>();

    public PublishingService(ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes,
                             ForGettingServerInfo forGettingServerInfo,
                             ForPersistingDnsRecords forPersistingDnsRecords,
                             ForGettingVpnClients forGettingVpnClients,
                             ForResolvingPeerNames forResolvingPeerNames,
                             ForGettingPeerConfigurations forGettingPeerConfigurations,
                             ConfigResolver configResolver,
                             ForPublishingEvents forPublishingEvents,
                             ForResolvingDns forResolvingDns,
                             ForManagingIgnoredServices forManagingIgnoredServices,
                             PendingPublicationsService pendingPublicationsService,
                             DiscoverPeerContainersUseCase discoverPeerContainersUseCase,
                             DiscoverLanDockerHostContainersUseCase discoverLanDockerHostContainersUseCase,
                             GetLocalDockerServicesUseCase getLocalDockerServicesUseCase) {
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
        this.forGettingServerInfo = forGettingServerInfo;
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forResolvingPeerNames = forResolvingPeerNames;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.configResolver = configResolver;
        this.forPublishingEvents = forPublishingEvents;
        this.forResolvingDns = forResolvingDns;
        this.forManagingIgnoredServices = forManagingIgnoredServices;
        this.pendingPublicationsService = pendingPublicationsService;
        this.discoverPeerContainersUseCase = discoverPeerContainersUseCase;
        this.discoverLanDockerHostContainersUseCase = discoverLanDockerHostContainersUseCase;
        this.getLocalDockerServicesUseCase = getLocalDockerServicesUseCase;
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

        List<DnsRecord> allDnsRecords = forPersistingDnsRecords.getDnsZones().stream()
            .flatMap(zone -> forPersistingDnsRecords.getDnsRecords(zone).stream())
            .toList();
        List<VpnClient> vpnClients = forGettingVpnClients.getClients();
        List<DockerService> localServices = forGettingServerInfo.getServicesWithExposedPorts(Server.local());

        cache = routes.stream()
            .filter(r -> !isInfrastructureRouter(r))
            .map(r -> toUco(r, allDnsRecords, vpnClients, localServices))
            .toList();
        return cache;
    }

    private boolean isInfrastructureRouter(ReverseProxyRoute route) {
        return PublishingConstants.MANDATORY_SUBDOMAINS.stream()
            .anyMatch(sub -> route.getDomainName().startsWith(sub + "."));
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
        var peers = forGettingPeerConfigurations.getAllPeerConfigs();
        return new PublishedServiceUco(
            route.displayName(configResolver.getDomain(), localServices, vpnClients, forResolvingPeerNames, peers),
            route.getDomainName(),
            route.dnsState(allDnsRecords),
            route.getAddress(),
            route.getPort(),
            route.hostState(localServices, vpnClients, peers),
            route.getAuthInfo() != null,
            route.getRootRedirectPath(),
            route.isDirectUrlDisabled(),
            route.isLanService()
        );
    }

    // --- PublishPeerServiceUseCase ---

    @Override
    public void publishService(String address, int port, String subdomain, boolean requiresAuth, String rootRedirectPath, boolean directUrlDisabled) {
        // A LAN docker host's IP arrives here when the user clicks "+ Publish" on a discovered
        // LAN_DOCKER_HOST service. Dispatch to the LAN flow so the route is marked isLanService=true
        // and the dashboard's direct-LAN URL bypass works (#180).
        if (hostInsideAnyRelayLanCidr(address)) {
            log.info("Address {} falls inside a relay's lanCidr — publishing as LAN service", address);
            publishLanService(subdomain, address, port, "http", requiresAuth, directUrlDisabled);
            return;
        }

        String fqdn = subdomain + "." + configResolver.getDomain();
        String serverFqdn = "vaier." + configResolver.getDomain();

        log.info("Publishing service: {} -> {}:{} (auth: {}, directUrlDisabled: {})", fqdn, address, port, requiresAuth, directUrlDisabled);

        DnsRecord cname = new DnsRecord(fqdn + ".", DnsRecordType.CNAME, 300L, List.of(serverFqdn + "."));
        DnsZone zone = new DnsZone(configResolver.getDomain());
        forPersistingDnsRecords.addDnsRecord(cname, zone);
        log.info("Created DNS CNAME {} -> {}", fqdn, serverFqdn);

        pendingPublishes.put(subdomain, new PendingState(requiresAuth, false));
        pendingPublicationsService.track(address, port);
        forPublishingEvents.publish("published-services", "publish-dns-created", subdomain);

        CompletableFuture.runAsync(() -> waitForDnsThenActivate(subdomain, fqdn, address, port, requiresAuth, rootRedirectPath, directUrlDisabled));
    }

    @Override
    public PublishStatus getPublishStatus(String subdomain) {
        String fqdn = subdomain + "." + configResolver.getDomain();
        boolean traefikActive = forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
            .anyMatch(r -> r.getDomainName().equals(fqdn));
        if (traefikActive) {
            pendingPublishes.remove(subdomain);
            return new PublishStatus(true, true);
        }
        PendingState state = pendingPublishes.getOrDefault(subdomain, new PendingState(false, false));
        return new PublishStatus(state.dnsPropagated(), false);
    }

    @Override
    public List<PendingPublication> getPendingPublications() {
        return pendingPublishes.entrySet().stream()
            .map(e -> new PendingPublication(e.getKey(), e.getValue().requiresAuth(), e.getValue().dnsPropagated()))
            .toList();
    }

    // --- PublishLanServiceUseCase ---

    @Override
    public void publishLanService(String subdomain, String host, int port, String protocol,
                                  boolean requiresAuth, boolean directUrlDisabled) {
        ReverseProxyRoute.validateForPublication(subdomain + "." + configResolver.getDomain(), host, port);
        String scheme = (protocol == null || protocol.isBlank()) ? "http" : protocol.toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("protocol must be http or https (was " + protocol + ")");
        }

        if (!hostInsideAnyRelayLanCidr(host)) {
            throw new IllegalArgumentException(
                "Target host " + host + " is not inside any relay peer's lanCidr. " +
                "Set lanCidr on the relay peer first.");
        }

        String fqdn = subdomain + "." + configResolver.getDomain();
        String serverFqdn = "vaier." + configResolver.getDomain();
        log.info("Publishing LAN service: {} -> {}://{}:{} (auth: {}, directUrlDisabled: {})",
            fqdn, scheme, host, port, requiresAuth, directUrlDisabled);

        DnsRecord cname = new DnsRecord(fqdn + ".", DnsRecordType.CNAME, 300L, List.of(serverFqdn + "."));
        DnsZone zone = new DnsZone(configResolver.getDomain());
        forPersistingDnsRecords.addDnsRecord(cname, zone);
        log.info("Created DNS CNAME {} -> {}", fqdn, serverFqdn);

        pendingPublishes.put(subdomain, new PendingState(requiresAuth, false));
        forPublishingEvents.publish("published-services", "publish-dns-created", subdomain);

        CompletableFuture.runAsync(() ->
            waitForLanDnsThenActivate(subdomain, fqdn, host, port, scheme, requiresAuth, directUrlDisabled));
    }

    private boolean hostInsideAnyRelayLanCidr(String host) {
        return forGettingPeerConfigurations.getAllPeerConfigs().stream()
            .map(PeerConfiguration::lanCidr)
            .filter(c -> c != null && !c.isBlank())
            .anyMatch(c -> {
                try { return Cidr.parse(c).contains(host); }
                catch (IllegalArgumentException e) { return false; }
            });
    }

    void waitForLanDnsThenActivate(String subdomain, String fqdn, String host, int port, String protocol,
                                   boolean requiresAuth, boolean directUrlDisabled) {
        long deadline = System.currentTimeMillis() + dnsTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (forResolvingDns.isResolvable(fqdn)) {
                log.info("DNS propagated for {}, activating Traefik LAN route", fqdn);
                pendingPublishes.compute(subdomain, (k, v) -> new PendingState(v != null && v.requiresAuth(), true));
                forPublishingEvents.publish("published-services", "publish-dns-propagated", subdomain);
                try {
                    forPersistingReverseProxyRoutes.addLanReverseProxyRoute(
                        fqdn, host, port, protocol, requiresAuth, directUrlDisabled);
                } catch (Exception e) {
                    log.error("Failed to write Traefik LAN route for {}: {}", fqdn, e.getMessage(), e);
                    rollbackLan(subdomain, fqdn, false);
                    return;
                }
                if (!waitForTraefikRoute(fqdn)) {
                    log.warn("Traefik did not pick up LAN route for {}; rolling back", fqdn);
                    rollbackLan(subdomain, fqdn, true);
                    return;
                }
                pendingPublishes.remove(subdomain);
                invalidatePublishedServicesCache();
                forPublishingEvents.publish("published-services", "publish-traefik-active", subdomain);
                forPublishingEvents.publish("published-services", "service-updated", subdomain);
                return;
            }
            try { Thread.sleep(dnsRetryIntervalMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("DNS propagation timed out for {} after {}s", fqdn, dnsTimeoutMillis / 1000);
        forPublishingEvents.publish("published-services", "publish-dns-timeout", subdomain);
        rollbackLan(subdomain, fqdn, false);
    }

    private void rollbackLan(String subdomain, String fqdn, boolean removeRoute) {
        if (removeRoute) {
            try { forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(fqdn); }
            catch (Exception e) { log.warn("Failed to remove Traefik route during LAN rollback for {}: {}", fqdn, e.getMessage()); }
        }
        try {
            forPersistingDnsRecords.deleteDnsRecord(fqdn + ".", DnsRecordType.CNAME,
                new DnsZone(configResolver.getDomain()));
            log.info("Rolled back CNAME for {}", fqdn);
        } catch (Exception e) {
            log.warn("Failed to remove CNAME during LAN rollback for {}: {}", fqdn, e.getMessage());
        }
        pendingPublishes.remove(subdomain);
        invalidatePublishedServicesCache();
        forPublishingEvents.publish("published-services", "publish-rolled-back", subdomain);
        forPublishingEvents.publish("published-services", "service-updated", subdomain);
    }

    void waitForDnsThenActivate(String subdomain, String fqdn, String address, int port, boolean requiresAuth, String rootRedirectPath, boolean directUrlDisabled) {
        long deadline = System.currentTimeMillis() + dnsTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (forResolvingDns.isResolvable(fqdn)) {
                log.info("DNS propagated for {}, activating Traefik route", fqdn);
                pendingPublishes.compute(subdomain, (k, v) -> new PendingState(v != null && v.requiresAuth(), true));
                forPublishingEvents.publish("published-services", "publish-dns-propagated", subdomain);
                String persistedAddress = forGettingServerInfo.findContainerNameByIp(Server.local(), address).orElse(address);
                if (!persistedAddress.equals(address)) {
                    log.info("Normalized backend address {} -> {} for {}", address, persistedAddress, fqdn);
                }
                try {
                    forPersistingReverseProxyRoutes.addReverseProxyRoute(fqdn, persistedAddress, port, requiresAuth, rootRedirectPath);
                } catch (Exception e) {
                    log.error("Failed to write Traefik route for {}: {}", fqdn, e.getMessage(), e);
                    rollback(subdomain, fqdn, address, port, false);
                    return;
                }
                if (directUrlDisabled) {
                    forPersistingReverseProxyRoutes.setRouteDirectUrlDisabled(fqdn, true);
                }
                log.info("Created Traefik route for {}", fqdn);
                if (!waitForTraefikRoute(fqdn)) {
                    log.warn("Traefik did not pick up route for {}; rolling back", fqdn);
                    rollback(subdomain, fqdn, address, port, true);
                    return;
                }
                pendingPublicationsService.untrack(address, port);
                pendingPublishes.remove(subdomain);
                invalidatePublishedServicesCache();
                forPublishingEvents.publish("published-services", "publish-traefik-active", subdomain);
                forPublishingEvents.publish("published-services", "service-updated", subdomain);
                return;
            }
            log.debug("DNS not yet live for {}, retrying in {}s", fqdn, dnsRetryIntervalMillis / 1000);
            try { Thread.sleep(dnsRetryIntervalMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("DNS propagation timed out for {} after {}s — Traefik route NOT written to avoid invalid certificate", fqdn, dnsTimeoutMillis / 1000);
        forPublishingEvents.publish("published-services", "publish-dns-timeout", subdomain);
        rollback(subdomain, fqdn, address, port, false);
    }

    private void rollback(String subdomain, String fqdn, String address, int port, boolean removeRoute) {
        if (removeRoute) {
            try {
                forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(fqdn);
            } catch (Exception e) {
                log.warn("Failed to remove Traefik route during rollback for {}: {}", fqdn, e.getMessage());
            }
        }
        try {
            forPersistingDnsRecords.deleteDnsRecord(fqdn + ".", DnsRecordType.CNAME,
                new DnsZone(configResolver.getDomain()));
            log.info("Rolled back CNAME for {}", fqdn);
        } catch (Exception e) {
            log.warn("Failed to remove CNAME during rollback for {}: {}", fqdn, e.getMessage());
        }
        pendingPublicationsService.untrack(address, port);
        pendingPublishes.remove(subdomain);
        invalidatePublishedServicesCache();
        forPublishingEvents.publish("published-services", "publish-rolled-back", subdomain);
        forPublishingEvents.publish("published-services", "service-updated", subdomain);
    }

    private boolean waitForTraefikRoute(String fqdn) {
        long deadline = System.currentTimeMillis() + traefikActivationTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            boolean active = forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
                .anyMatch(r -> r.getDomainName().equals(fqdn));
            if (active) {
                log.info("Traefik picked up route for {}", fqdn);
                return true;
            }
            log.debug("Waiting for Traefik to pick up route for {}", fqdn);
            try { Thread.sleep(traefikActivationRetryIntervalMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    // --- DeletePublishedServiceUseCase ---

    @Override
    public void deleteService(String fqdn) {
        boolean isMandatory = PublishingConstants.MANDATORY_SUBDOMAINS.stream()
            .anyMatch(sub -> fqdn.equals(sub + "." + configResolver.getDomain()));
        if (isMandatory) {
            throw new IllegalArgumentException("Cannot delete built-in service: " + fqdn);
        }
        log.info("Deleting service: {}", fqdn);

        forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(fqdn);
        log.info("Deleted Traefik route for {}", fqdn);

        waitForTraefikRouteDeletion(fqdn);

        forPersistingDnsRecords.deleteDnsRecord(fqdn, DnsRecordType.CNAME, new DnsZone(configResolver.getDomain()));
        log.info("Deleted DNS CNAME for {}", fqdn);
        invalidatePublishedServicesCache();
    }

    private void waitForTraefikRouteDeletion(String fqdn) {
        long deadline = System.currentTimeMillis() + 15_000;
        int consecutiveAbsent = 0;
        while (System.currentTimeMillis() < deadline) {
            boolean stillPresent = forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
                .anyMatch(r -> r.getDomainName().equals(fqdn));
            if (!stillPresent) {
                consecutiveAbsent++;
                if (consecutiveAbsent >= 2) {
                    log.info("Traefik confirmed route deletion for {}", fqdn);
                    return;
                }
            } else {
                consecutiveAbsent = 0;
            }
            log.debug("Waiting for Traefik to remove route for {}", fqdn);
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("Traefik did not remove route for {} within 15s, proceeding anyway", fqdn);
    }

    // --- GetPublishableServicesUseCase ---

    @Override
    public List<PublishableService> getPublishableServices() {
        var existingRoutes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        var publishable = new ArrayList<PublishableService>();

        discoverPeerContainersUseCase.discoverAll().stream()
            .filter(peer -> "OK".equals(peer.status()))
            .flatMap(peer -> peer.containers().stream()
                .flatMap(container -> container.ports().stream()
                    .filter(p -> "tcp".equals(p.type()))
                    .filter(p -> p.publicPort() != null)
                    .filter(p -> existingRoutes.stream()
                        .noneMatch(r -> r.getAddress().equals(peer.vpnIp()) && r.getPort() == p.publicPort()))
                    .filter(p -> !pendingPublicationsService.isPending(peer.vpnIp(), p.publicPort()))
                    .map(p -> new PublishableService(PublishableSource.PEER, peer.peerName(), peer.vpnIp(), container.containerName(), p.publicPort(), null, false))
                )
            )
            .forEach(publishable::add);

        discoverLanDockerHostContainersUseCase.discoverAllLanDockerHostContainers().stream()
            .filter(host -> "OK".equals(host.status()))
            .flatMap(host -> host.containers().stream()
                .flatMap(container -> container.ports().stream()
                    .filter(p -> "tcp".equals(p.type()))
                    .filter(p -> p.publicPort() != null)
                    .filter(p -> existingRoutes.stream()
                        .noneMatch(r -> r.getAddress().equals(host.hostIp()) && r.getPort() == p.publicPort()))
                    .filter(p -> !pendingPublicationsService.isPending(host.hostIp(), p.publicPort()))
                    .map(p -> new PublishableService(PublishableSource.LAN_DOCKER_HOST, host.relayPeerName(),
                        host.hostIp(), container.containerName(), p.publicPort(), null, false))
                )
            )
            .forEach(publishable::add);

        getLocalDockerServicesUseCase.getUnpublishedLocalServices(existingRoutes).stream()
            .filter(s -> !pendingPublicationsService.isPending(s.address(), s.port()))
            .forEach(publishable::add);

        Set<String> ignoredKeys = forManagingIgnoredServices.getIgnoredServiceKeys();
        return publishable.stream().distinct()
            .map(s -> new PublishableService(s.source(), s.peerName(), s.address(), s.containerName(), s.port(), s.rootRedirectPath(), ignoredKeys.contains(ignoreKey(s))))
            .toList();
    }

    static String ignoreKey(PublishableService s) {
        return switch (s.source()) {
            case PEER            -> s.peerName() + "/" + s.containerName() + ":" + s.port();
            case LAN_DOCKER_HOST -> s.address()  + "/" + s.containerName() + ":" + s.port();
            case LOCAL           -> s.containerName() + ":" + s.port();
        };
    }

    // --- ToggleServiceAuthUseCase ---

    @Override
    public void setAuthentication(String dnsName, boolean requiresAuth) {
        requireNonMandatory(dnsName, "Cannot change auth for built-in service: ");
        log.info("Setting auth={} for {}", requiresAuth, dnsName);
        forPersistingReverseProxyRoutes.setRouteAuthentication(dnsName, requiresAuth);
        invalidatePublishedServicesCache();
    }

    // --- ToggleServiceDirectUrlDisabledUseCase ---

    @Override
    public void setDirectUrlDisabled(String dnsName, boolean directUrlDisabled) {
        requireNonMandatory(dnsName, "Cannot change direct URL setting for built-in service: ");
        log.info("Setting directUrlDisabled={} for {}", directUrlDisabled, dnsName);
        forPersistingReverseProxyRoutes.setRouteDirectUrlDisabled(dnsName, directUrlDisabled);
        invalidatePublishedServicesCache();
    }

    // --- EditServiceRedirectUseCase ---

    @Override
    public void setRootRedirectPath(String dnsName, String rootRedirectPath) {
        requireNonMandatory(dnsName, "Cannot edit built-in service: ");
        log.info("Setting root redirect path for {} to {}", dnsName, rootRedirectPath);
        forPersistingReverseProxyRoutes.setRouteRootRedirectPath(dnsName, rootRedirectPath);
        invalidatePublishedServicesCache();
    }

    private void requireNonMandatory(String dnsName, String errorPrefix) {
        boolean isMandatory = PublishingConstants.MANDATORY_SUBDOMAINS.stream()
            .anyMatch(sub -> dnsName.equals(sub + "." + configResolver.getDomain()));
        if (isMandatory) {
            throw new IllegalArgumentException(errorPrefix + dnsName);
        }
    }

    // --- IgnorePublishableServiceUseCase ---

    @Override
    public void ignoreService(String key) {
        forManagingIgnoredServices.ignoreService(key);
    }

    // --- UnignorePublishableServiceUseCase ---

    @Override
    public void unignoreService(String key) {
        forManagingIgnoredServices.unignoreService(key);
    }
}
