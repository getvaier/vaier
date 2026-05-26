package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.DiscoverLanServerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
import net.vaier.application.EditServiceLaunchpadAliasUseCase;
import net.vaier.application.EditServiceRedirectUseCase;
import net.vaier.application.EditServiceVersionEndpointUseCase;
import net.vaier.application.GetLanServerScrapeUseCase;
import net.vaier.application.GetLaunchpadServicesUseCase;
import net.vaier.application.GetVaierServerDockerServicesUseCase;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.IgnorePublishableServiceUseCase;
import net.vaier.application.PublishLanServiceUseCase;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.application.PublishedServicesCacheInvalidator;
import net.vaier.application.PublishingConstants;
import net.vaier.application.RefreshLaunchpadVersionsUseCase;
import net.vaier.application.ToggleServiceAuthUseCase;
import net.vaier.application.ToggleServiceDirectUrlDisabledUseCase;
import net.vaier.application.ToggleServiceLaunchpadVisibilityUseCase;
import net.vaier.application.UnignorePublishableServiceUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsState;
import net.vaier.domain.VaierHostnames;
import net.vaier.domain.DnsZone;
import net.vaier.domain.DockerService;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.LaunchpadVisibility;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Server;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForManagingIgnoredServices;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForProbingServiceVersion;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForResolvingDns;
import net.vaier.domain.port.ForResolvingPeerNames;
import net.vaier.domain.port.ForResolvingServerLanCidr;
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
    ToggleServiceLaunchpadVisibilityUseCase,
    EditServiceRedirectUseCase,
    EditServiceLaunchpadAliasUseCase,
    EditServiceVersionEndpointUseCase,
    IgnorePublishableServiceUseCase,
    UnignorePublishableServiceUseCase,
    RefreshLaunchpadVersionsUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForGettingServerInfo forGettingServerInfo;
    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForResolvingPeerNames forResolvingPeerNames;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForResolvingServerLanCidr forResolvingServerLanCidr;
    private final ForPersistingLanServers forPersistingLanServers;
    private final ConfigResolver configResolver;
    private final ForPublishingEvents forPublishingEvents;
    private final ForResolvingDns forResolvingDns;
    private final ForManagingIgnoredServices forManagingIgnoredServices;
    private final PendingPublicationsService pendingPublicationsService;
    private final DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    private final DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase;
    private final GetLanServerScrapeUseCase getLanServerScrapeUseCase;
    private final GetVaierServerDockerServicesUseCase getVaierServerDockerServicesUseCase;
    private final ForProbingServiceVersion forProbingServiceVersion;

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
                             ForResolvingServerLanCidr forResolvingServerLanCidr,
                             ForPersistingLanServers forPersistingLanServers,
                             ConfigResolver configResolver,
                             ForPublishingEvents forPublishingEvents,
                             ForResolvingDns forResolvingDns,
                             ForManagingIgnoredServices forManagingIgnoredServices,
                             PendingPublicationsService pendingPublicationsService,
                             DiscoverPeerContainersUseCase discoverPeerContainersUseCase,
                             DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase,
                             GetLanServerScrapeUseCase getLanServerScrapeUseCase,
                             GetVaierServerDockerServicesUseCase getVaierServerDockerServicesUseCase,
                             ForProbingServiceVersion forProbingServiceVersion) {
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
        this.forGettingServerInfo = forGettingServerInfo;
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forResolvingPeerNames = forResolvingPeerNames;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forResolvingServerLanCidr = forResolvingServerLanCidr;
        this.forPersistingLanServers = forPersistingLanServers;
        this.configResolver = configResolver;
        this.forPublishingEvents = forPublishingEvents;
        this.forResolvingDns = forResolvingDns;
        this.forManagingIgnoredServices = forManagingIgnoredServices;
        this.pendingPublicationsService = pendingPublicationsService;
        this.discoverPeerContainersUseCase = discoverPeerContainersUseCase;
        this.discoverVaierServerContainersUseCase = discoverVaierServerContainersUseCase;
        this.getLanServerScrapeUseCase = getLanServerScrapeUseCase;
        this.getVaierServerDockerServicesUseCase = getVaierServerDockerServicesUseCase;
        this.forProbingServiceVersion = forProbingServiceVersion;
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
        List<DockerService> localServices = forGettingServerInfo.getServicesWithExposedPorts(Server.vaierServer());
        String serverLanCidr = forResolvingServerLanCidr.resolve().orElse(null);

        cache = routes.stream()
            .filter(r -> !isInfrastructureRouter(r))
            .map(r -> toUco(r, allDnsRecords, vpnClients, localServices, serverLanCidr))
            .toList();
        return cache;
    }

    private boolean isInfrastructureRouter(ReverseProxyRoute route) {
        return PublishingConstants.isMandatory(route.getDomainName(), configResolver.getDomain());
    }

    @Override
    public List<LaunchpadServiceUco> getLaunchpadServices(String callerIp, boolean callerAuthenticated) {
        List<PublishedServiceUco> published = getPublishedServices();
        if (published.isEmpty()) return List.of();

        List<PeerConfiguration> peers = forGettingPeerConfigurations.getAllPeerConfigs();
        List<VpnClient> vpnClients = forGettingVpnClients.getClients();
        List<ReverseProxyRoute> routes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        String baseDomain = configResolver.getDomain();
        ContainerImageSnapshot images = currentContainerImages();
        Map<String, String> probedVersions = launchpadVersions;
        // Match each enriched Uco back to its route by (dnsAddress, pathPrefix), then ask the
        // domain for the consolidated launchpad visibility, tile label, and backing container.
        // NOT_VISIBLE entries are dropped; the rest carry the tri-state, display name, and — when
        // a container backs the route — its running Docker image/version through so the launchpad
        // client doesn't have to know why a service is shown, how it's labelled, or what runs it.
        return published.stream()
            .flatMap(s -> ReverseProxyRoute
                .findByFqdnAndPath(routes, s.dnsAddress(), s.pathPrefix())
                .map(r -> {
                    LaunchpadVisibility visibility = r.launchpadVisibility(s.dnsState(), s.state(), callerAuthenticated);
                    if (visibility == LaunchpadVisibility.NOT_VISIBLE) return null;
                    DockerService backing = r.backingContainer(images.vaierServerContainers(),
                        images.peerContainersByVpnIp(), images.lanServerContainersByAddress()).orElse(null);
                    // A configured version endpoint (a service running natively on a LAN machine,
                    // reporting its own version over HTTP) takes precedence over a container's
                    // image tag; the image still comes only from a backing container, if any.
                    String probedVersion = r.hasVersionEndpoint()
                        ? probedVersions.get(r.getName()) : null;
                    return new LaunchpadServiceUco(s.dnsAddress(), s.pathPrefix(), s.hostAddress(),
                        visibility, r.launchpadUrl(callerIp, peers, vpnClients, baseDomain),
                        r.launchpadDisplayName(baseDomain), r.launchpadFaviconQuery(),
                        r.hostDisplayName(vpnClients, forResolvingPeerNames, peers),
                        backing == null ? null : backing.image(),
                        probedVersion != null ? probedVersion
                            : (backing == null ? null : backing.version()));
                })
                .filter(java.util.Objects::nonNull)
                .stream())
            .toList();
    }

    private record ContainerImageSnapshot(
        List<DockerService> vaierServerContainers,
        Map<String, List<DockerService>> peerContainersByVpnIp,
        Map<String, List<DockerService>> lanServerContainersByAddress) {}

    /**
     * Assembles the discovered-container view used to resolve each route's backing image
     * (issue #210). Cheap to build per request: peer and LAN containers are served from the
     * state-refresh caches, and only the local Docker socket is read live (sub-millisecond).
     */
    private ContainerImageSnapshot currentContainerImages() {
        Map<String, List<DockerService>> peerContainers = discoverPeerContainersUseCase.discoverAll().stream()
            .filter(p -> p.vpnIp() != null)
            .collect(java.util.stream.Collectors.toMap(
                DiscoverPeerContainersUseCase.PeerContainers::vpnIp,
                DiscoverPeerContainersUseCase.PeerContainers::containers,
                (a, b) -> a));
        Map<String, List<DockerService>> lanServerContainers = getLanServerScrapeUseCase
            .getLanServerContainers().stream()
            .filter(h -> h.lanAddress() != null)
            .collect(java.util.stream.Collectors.toMap(
                DiscoverLanServerContainersUseCase.LanServerContainers::lanAddress,
                DiscoverLanServerContainersUseCase.LanServerContainers::containers,
                (a, b) -> a));
        return new ContainerImageSnapshot(
            discoverVaierServerContainersUseCase.discover(),
            peerContainers, lanServerContainers);
    }

    /**
     * Router name → probed version, for every route with a configured version endpoint
     * (issue #210). Served to the launchpad as a plain cache read; populated only by
     * {@link #refreshLaunchpadVersions()}, which the state-refresh scheduler drives.
     */
    private volatile Map<String, String> launchpadVersions = Map.of();

    @Override
    public void refreshLaunchpadVersions() {
        // The route owns the probe — it talks to the ForProbingServiceVersion driven port itself
        // (see ReverseProxyRoute#probeVersion). This service only orchestrates: pick the routes,
        // run the probes concurrently, cache the result. It must not call the port directly.
        List<CompletableFuture<Map.Entry<String, String>>> probes =
            forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
                .filter(ReverseProxyRoute::hasVersionEndpoint)
                .map(r -> CompletableFuture.supplyAsync(() ->
                    r.probeVersion(forProbingServiceVersion)
                        .map(v -> Map.entry(r.getName(), v))
                        .orElse(null)))
                .toList();
        launchpadVersions = probes.stream()
            .map(CompletableFuture::join)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    private PublishedServiceUco toUco(ReverseProxyRoute route, List<DnsRecord> allDnsRecords,
                                    List<VpnClient> vpnClients, List<DockerService> localServices,
                                    String serverLanCidr) {
        var peers = forGettingPeerConfigurations.getAllPeerConfigs();
        DnsState dnsState = route.dnsState(allDnsRecords, configResolver.getDnsProvider());
        return new PublishedServiceUco(
            route.displayName(configResolver.getDomain(), localServices, vpnClients, forResolvingPeerNames, peers),
            route.getDomainName(),
            dnsState,
            route.getAddress(),
            route.getPort(),
            route.hostState(localServices, vpnClients, peers, serverLanCidr),
            route.getAuthInfo() != null,
            route.getRootRedirectPath(),
            route.isDirectUrlDisabled(),
            route.isLanService(),
            route.getPathPrefix(),
            route.isHiddenFromLaunchpad(),
            route.getLaunchpadAlias(),
            route.getVersionEndpoint(),
            route.getVersionProperty()
        );
    }

    // --- PublishPeerServiceUseCase ---

    @Override
    public void publishService(String address, int port, String subdomain, boolean requiresAuth,
                               String rootRedirectPath, boolean directUrlDisabled, String pathPrefix) {
        String normalisedPath = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        ReverseProxyRoute.validatePathPrefix(normalisedPath);

        // A LAN docker host's IP arrives here when the user clicks "+ Publish" on a discovered
        // LAN_SERVER service. Dispatch to the LAN flow so the route is marked isLanService=true
        // and the dashboard's direct-LAN URL bypass works (#180). The address may be on a relay
        // peer's LAN or in the Vaier server's own subnet (server LAN CIDR).
        if (hostInsideAnyLanCidr(address)) {
            log.info("Address {} falls inside a relay peer's or the Vaier server's LAN CIDR — publishing as LAN service", address);
            publishLanRoute(subdomain, address, port, "http", requiresAuth, directUrlDisabled, rootRedirectPath, normalisedPath);
            return;
        }

        String fqdn = subdomain + "." + configResolver.getDomain();
        String serverFqdn = new VaierHostnames(configResolver.getDomain()).vaierServerFqdn();

        List<ReverseProxyRoute> existing = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        if (ReverseProxyRoute.conflictsWithExisting(existing, fqdn, normalisedPath)) {
            throw new IllegalArgumentException(
                "A route already exists on " + fqdn +
                (normalisedPath == null ? " (host-only)" : " for path " + normalisedPath));
        }

        log.info("Publishing service: {} -> {}:{} (auth: {}, directUrlDisabled: {}, pathPrefix: {})",
            fqdn, address, port, requiresAuth, directUrlDisabled, normalisedPath);

        if (ReverseProxyRoute.hasSiblingOnHost(existing, fqdn)) {
            log.info("Skipping DNS create for {} — sibling route already on this host", fqdn);
        } else {
            DnsRecord cname = new DnsRecord(fqdn + ".", DnsRecordType.CNAME, 300L, List.of(serverFqdn + "."));
            DnsZone zone = new DnsZone(configResolver.getDomain());
            forPersistingDnsRecords.addDnsRecord(cname, zone);
            log.info("Created DNS CNAME {} -> {}", fqdn, serverFqdn);
        }

        pendingPublishes.put(subdomain, new PendingState(requiresAuth, false));
        pendingPublicationsService.track(address, port);
        forPublishingEvents.publish("published-services", "publish-dns-created", subdomain);

        CompletableFuture.runAsync(() -> waitForDnsThenActivate(subdomain, fqdn, address, port, requiresAuth, rootRedirectPath, directUrlDisabled, normalisedPath));
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
    public void publishLanService(String subdomain, String machineName, int port, String protocol,
                                  boolean requiresAuth, boolean directUrlDisabled, String rootRedirectPath,
                                  String pathPrefix) {
        LanServer machine = LanServer.findByName(machineName, forPersistingLanServers.getAll())
            .orElseThrow(() -> new IllegalArgumentException("Unknown machine: " + machineName));
        publishLanRoute(subdomain, machine.lanAddress(), port, protocol,
            requiresAuth, directUrlDisabled, rootRedirectPath, pathPrefix);
    }

    private void publishLanRoute(String subdomain, String host, int port, String protocol,
                                 boolean requiresAuth, boolean directUrlDisabled, String rootRedirectPath,
                                 String pathPrefix) {
        String normalisedPath = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        ReverseProxyRoute.validatePathPrefix(normalisedPath);
        ReverseProxyRoute.validateForPublication(subdomain + "." + configResolver.getDomain(), host, port);
        String scheme = ReverseProxyRoute.normaliseProtocol(protocol);
        ReverseProxyRoute.validateProtocol(scheme);

        if (!hostInsideAnyLanCidr(host)) {
            throw new IllegalArgumentException(
                "Target host " + host + " is not inside any relay peer's lanCidr, " +
                "nor inside the Vaier server's own LAN CIDR. Set lanCidr on a relay peer first " +
                "(or, on EC2, the server LAN CIDR is auto-detected from instance metadata).");
        }

        String fqdn = subdomain + "." + configResolver.getDomain();
        String serverFqdn = new VaierHostnames(configResolver.getDomain()).vaierServerFqdn();

        List<ReverseProxyRoute> existing = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        if (ReverseProxyRoute.conflictsWithExisting(existing, fqdn, normalisedPath)) {
            throw new IllegalArgumentException(
                "A route already exists on " + fqdn +
                (normalisedPath == null ? " (host-only)" : " for path " + normalisedPath));
        }

        log.info("Publishing LAN service: {} -> {}://{}:{} (auth: {}, directUrlDisabled: {}, rootRedirectPath: {}, pathPrefix: {})",
            fqdn, scheme, host, port, requiresAuth, directUrlDisabled, rootRedirectPath, normalisedPath);

        if (ReverseProxyRoute.hasSiblingOnHost(existing, fqdn)) {
            log.info("Skipping DNS create for {} — sibling route already on this host", fqdn);
        } else {
            DnsRecord cname = new DnsRecord(fqdn + ".", DnsRecordType.CNAME, 300L, List.of(serverFqdn + "."));
            DnsZone zone = new DnsZone(configResolver.getDomain());
            forPersistingDnsRecords.addDnsRecord(cname, zone);
            log.info("Created DNS CNAME {} -> {}", fqdn, serverFqdn);
        }

        pendingPublishes.put(subdomain, new PendingState(requiresAuth, false));
        forPublishingEvents.publish("published-services", "publish-dns-created", subdomain);

        CompletableFuture.runAsync(() ->
            waitForLanDnsThenActivate(subdomain, fqdn, host, port, scheme, requiresAuth, directUrlDisabled, rootRedirectPath, normalisedPath));
    }

    private boolean hostInsideAnyLanCidr(String host) {
        return LanAnchor.resolve(host,
            forGettingPeerConfigurations.getAllPeerConfigs(),
            forResolvingServerLanCidr.resolve().orElse(null)).isPresent();
    }

    void waitForLanDnsThenActivate(String subdomain, String fqdn, String host, int port, String protocol,
                                   boolean requiresAuth, boolean directUrlDisabled, String rootRedirectPath,
                                   String pathPrefix) {
        long deadline = System.currentTimeMillis() + dnsTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (forResolvingDns.isResolvable(fqdn)) {
                log.info("DNS propagated for {}, activating Traefik LAN route", fqdn);
                pendingPublishes.compute(subdomain, (k, v) -> new PendingState(v != null && v.requiresAuth(), true));
                forPublishingEvents.publish("published-services", "publish-dns-propagated", subdomain);
                try {
                    forPersistingReverseProxyRoutes.addLanReverseProxyRoute(
                        fqdn, host, port, protocol, requiresAuth, directUrlDisabled, rootRedirectPath, pathPrefix);
                } catch (Exception e) {
                    log.error("Failed to write Traefik LAN route for {}: {}", fqdn, e.getMessage(), e);
                    rollbackLan(subdomain, fqdn, false, pathPrefix);
                    return;
                }
                if (!waitForTraefikRoute(fqdn)) {
                    log.warn("Traefik did not pick up LAN route for {}; rolling back", fqdn);
                    rollbackLan(subdomain, fqdn, true, pathPrefix);
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
        rollbackLan(subdomain, fqdn, false, pathPrefix);
    }

    private void rollbackLan(String subdomain, String fqdn, boolean removeRoute, String pathPrefix) {
        if (removeRoute) {
            try {
                ReverseProxyRoute.findByFqdnAndPath(
                        forPersistingReverseProxyRoutes.getReverseProxyRoutes(), fqdn, pathPrefix)
                    .ifPresentOrElse(
                        r -> forPersistingReverseProxyRoutes.deleteReverseProxyRoute(r.getName()),
                        () -> forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(fqdn));
            } catch (Exception e) {
                log.warn("Failed to remove Traefik route during LAN rollback for {}: {}", fqdn, e.getMessage());
            }
        }
        List<ReverseProxyRoute> remaining = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        if (ReverseProxyRoute.hasSiblingOnHost(remaining, fqdn)) {
            log.info("Skipping LAN CNAME rollback for {} — sibling routes remain", fqdn);
        } else {
            try {
                forPersistingDnsRecords.deleteDnsRecord(fqdn + ".", DnsRecordType.CNAME,
                    new DnsZone(configResolver.getDomain()));
                log.info("Rolled back CNAME for {}", fqdn);
            } catch (Exception e) {
                log.warn("Failed to remove CNAME during LAN rollback for {}: {}", fqdn, e.getMessage());
            }
        }
        pendingPublishes.remove(subdomain);
        invalidatePublishedServicesCache();
        forPublishingEvents.publish("published-services", "publish-rolled-back", subdomain);
        forPublishingEvents.publish("published-services", "service-updated", subdomain);
    }

    void waitForDnsThenActivate(String subdomain, String fqdn, String address, int port, boolean requiresAuth,
                                String rootRedirectPath, boolean directUrlDisabled, String pathPrefix) {
        long deadline = System.currentTimeMillis() + dnsTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (forResolvingDns.isResolvable(fqdn)) {
                log.info("DNS propagated for {}, activating Traefik route", fqdn);
                pendingPublishes.compute(subdomain, (k, v) -> new PendingState(v != null && v.requiresAuth(), true));
                forPublishingEvents.publish("published-services", "publish-dns-propagated", subdomain);
                String persistedAddress = forGettingServerInfo.findContainerNameByIp(Server.vaierServer(), address).orElse(address);
                if (!persistedAddress.equals(address)) {
                    log.info("Normalized backend address {} -> {} for {}", address, persistedAddress, fqdn);
                }
                try {
                    forPersistingReverseProxyRoutes.addReverseProxyRoute(fqdn, persistedAddress, port, requiresAuth, rootRedirectPath, pathPrefix);
                } catch (Exception e) {
                    log.error("Failed to write Traefik route for {}: {}", fqdn, e.getMessage(), e);
                    rollback(subdomain, fqdn, address, port, false, pathPrefix);
                    return;
                }
                if (directUrlDisabled) {
                    forPersistingReverseProxyRoutes.setRouteDirectUrlDisabled(fqdn, pathPrefix, true);
                }
                log.info("Created Traefik route for {}", fqdn);
                if (!waitForTraefikRoute(fqdn)) {
                    log.warn("Traefik did not pick up route for {}; rolling back", fqdn);
                    rollback(subdomain, fqdn, address, port, true, pathPrefix);
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
        rollback(subdomain, fqdn, address, port, false, pathPrefix);
    }

    private void rollback(String subdomain, String fqdn, String address, int port, boolean removeRoute, String pathPrefix) {
        if (removeRoute) {
            try {
                ReverseProxyRoute.findByFqdnAndPath(
                        forPersistingReverseProxyRoutes.getReverseProxyRoutes(), fqdn, pathPrefix)
                    .ifPresentOrElse(
                        r -> forPersistingReverseProxyRoutes.deleteReverseProxyRoute(r.getName()),
                        () -> forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(fqdn));
            } catch (Exception e) {
                log.warn("Failed to remove Traefik route during rollback for {}: {}", fqdn, e.getMessage());
            }
        }
        // Only roll the CNAME back if no sibling routes still depend on it — the domain helper
        // decides what "sibling" means.
        List<ReverseProxyRoute> remaining = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        if (ReverseProxyRoute.hasSiblingOnHost(remaining, fqdn)) {
            log.info("Skipping CNAME rollback for {} — sibling routes remain", fqdn);
        } else {
            try {
                forPersistingDnsRecords.deleteDnsRecord(fqdn + ".", DnsRecordType.CNAME,
                    new DnsZone(configResolver.getDomain()));
                log.info("Rolled back CNAME for {}", fqdn);
            } catch (Exception e) {
                log.warn("Failed to remove CNAME during rollback for {}: {}", fqdn, e.getMessage());
            }
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
    public void deleteService(String fqdn, String pathPrefix) {
        String normalisedPath = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        ReverseProxyRoute.validatePathPrefix(normalisedPath);

        if (PublishingConstants.isMandatory(fqdn, configResolver.getDomain())) {
            throw new IllegalArgumentException("Cannot delete built-in service: " + fqdn);
        }
        log.info("Deleting service: {} (pathPrefix: {})", fqdn, normalisedPath);

        if (normalisedPath == null) {
            // Legacy host-only delete: remove all routes via the dnsName-keyed helper, which
            // resolves to the conventional <fqdn>-router name. Preserves prior behaviour.
            forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(fqdn);
        } else {
            // Path-based delete: find the specific route and remove only it.
            List<ReverseProxyRoute> existing = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
            ReverseProxyRoute target = ReverseProxyRoute.findByFqdnAndPath(existing, fqdn, normalisedPath)
                .orElseThrow(() -> new IllegalArgumentException(
                    "No route found for " + fqdn + " with pathPrefix " + normalisedPath));
            forPersistingReverseProxyRoutes.deleteReverseProxyRoute(target.getName());
        }
        log.info("Deleted Traefik route for {} ({})", fqdn, normalisedPath);

        waitForTraefikRouteDeletion(fqdn, normalisedPath);

        // Only delete the CNAME when no sibling routes still use this host. The domain helper
        // decides what "sibling" means; the service just orchestrates.
        List<ReverseProxyRoute> remaining = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        if (ReverseProxyRoute.hasSiblingOnHost(remaining, fqdn)) {
            log.info("Skipping DNS delete for {} — sibling routes still on this host", fqdn);
        } else {
            forPersistingDnsRecords.deleteDnsRecord(fqdn, DnsRecordType.CNAME, new DnsZone(configResolver.getDomain()));
            log.info("Deleted DNS CNAME for {}", fqdn);
        }
        invalidatePublishedServicesCache();
    }

    private void waitForTraefikRouteDeletion(String fqdn, String pathPrefix) {
        long deadline = System.currentTimeMillis() + 15_000;
        int consecutiveAbsent = 0;
        while (System.currentTimeMillis() < deadline) {
            // For host-only delete, "absent" means no route on this fqdn at all. For path-based
            // delete, "absent" means no route on this (fqdn, pathPrefix). The domain knows what
            // route uniqueness means.
            boolean stillPresent = pathPrefix == null
                ? forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
                    .anyMatch(r -> r.getDomainName().equals(fqdn))
                : ReverseProxyRoute.findByFqdnAndPath(
                    forPersistingReverseProxyRoutes.getReverseProxyRoutes(), fqdn, pathPrefix).isPresent();
            if (!stillPresent) {
                consecutiveAbsent++;
                if (consecutiveAbsent >= 2) {
                    log.info("Traefik confirmed route deletion for {} ({})", fqdn, pathPrefix);
                    return;
                }
            } else {
                consecutiveAbsent = 0;
            }
            log.debug("Waiting for Traefik to remove route for {} ({})", fqdn, pathPrefix);
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
                    .filter(p -> !p.isRange())
                    .filter(p -> p.publicPort() != null)
                    .filter(p -> existingRoutes.stream()
                        .noneMatch(r -> r.getAddress().equals(peer.vpnIp()) && r.getPort() == p.publicPort()))
                    .filter(p -> !pendingPublicationsService.isPending(peer.vpnIp(), p.publicPort()))
                    .map(p -> new PublishableService(PublishableSource.PEER, peer.peerName(), peer.vpnIp(), container.containerName(), p.publicPort(), null, false))
                )
            )
            .forEach(publishable::add);

        getLanServerScrapeUseCase.getLanServerContainers().stream()
            .filter(host -> "OK".equals(host.status()))
            .flatMap(host -> host.containers().stream()
                .flatMap(container -> container.ports().stream()
                    .filter(p -> "tcp".equals(p.type()))
                    .filter(p -> !p.isRange())
                    .filter(p -> p.publicPort() != null)
                    .filter(p -> existingRoutes.stream()
                        .noneMatch(r -> r.getAddress().equals(host.lanAddress()) && r.getPort() == p.publicPort()))
                    .filter(p -> !pendingPublicationsService.isPending(host.lanAddress(), p.publicPort()))
                    .map(p -> new PublishableService(PublishableSource.LAN_SERVER, host.relayPeerName(),
                        host.lanAddress(), container.containerName(), p.publicPort(), null, false))
                )
            )
            .forEach(publishable::add);

        getVaierServerDockerServicesUseCase.getUnpublishedVaierServerServices(existingRoutes).stream()
            .filter(s -> !pendingPublicationsService.isPending(s.address(), s.port()))
            .forEach(publishable::add);

        Set<String> ignoredKeys = forManagingIgnoredServices.getIgnoredServiceKeys();
        return publishable.stream().distinct()
            .map(s -> new PublishableService(s.source(), s.peerName(), s.address(), s.containerName(), s.port(), s.rootRedirectPath(), ignoredKeys.contains(s.ignoreKey())))
            .toList();
    }

    // --- ToggleServiceAuthUseCase ---

    @Override
    public void setAuthentication(String dnsName, String pathPrefix, boolean requiresAuth) {
        String normalisedPath = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        ReverseProxyRoute.validatePathPrefix(normalisedPath);
        requireNonMandatory(dnsName, "Cannot change auth for built-in service: ");
        log.info("Setting auth={} for {} ({})", requiresAuth, dnsName, normalisedPath);
        forPersistingReverseProxyRoutes.setRouteAuthentication(dnsName, normalisedPath, requiresAuth);
        invalidatePublishedServicesCache();
    }

    // --- ToggleServiceDirectUrlDisabledUseCase ---

    @Override
    public void setDirectUrlDisabled(String dnsName, String pathPrefix, boolean directUrlDisabled) {
        String normalisedPath = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        ReverseProxyRoute.validatePathPrefix(normalisedPath);
        requireNonMandatory(dnsName, "Cannot change direct URL setting for built-in service: ");
        log.info("Setting directUrlDisabled={} for {} ({})", directUrlDisabled, dnsName, normalisedPath);
        forPersistingReverseProxyRoutes.setRouteDirectUrlDisabled(dnsName, normalisedPath, directUrlDisabled);
        invalidatePublishedServicesCache();
    }

    // --- ToggleServiceLaunchpadVisibilityUseCase ---

    @Override
    public void setHiddenFromLaunchpad(String dnsName, String pathPrefix, boolean hiddenFromLaunchpad) {
        String normalisedPath = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        ReverseProxyRoute.validatePathPrefix(normalisedPath);
        requireNonMandatory(dnsName, "Cannot change launchpad visibility for built-in service: ");
        log.info("Setting hiddenFromLaunchpad={} for {} ({})", hiddenFromLaunchpad, dnsName, normalisedPath);
        forPersistingReverseProxyRoutes.setRouteHiddenFromLaunchpad(dnsName, normalisedPath, hiddenFromLaunchpad);
        invalidatePublishedServicesCache();
    }

    // --- EditServiceLaunchpadAliasUseCase ---

    @Override
    public void setLaunchpadAlias(String dnsName, String pathPrefix, String launchpadAlias) {
        String normalisedPath = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        ReverseProxyRoute.validatePathPrefix(normalisedPath);
        requireNonMandatory(dnsName, "Cannot set launchpad alias for built-in service: ");
        String alias = (launchpadAlias == null || launchpadAlias.isBlank()) ? null : launchpadAlias.trim();
        log.info("Setting launchpadAlias={} for {} ({})", alias, dnsName, normalisedPath);
        forPersistingReverseProxyRoutes.setRouteLaunchpadAlias(dnsName, normalisedPath, alias);
        invalidatePublishedServicesCache();
    }

    // --- EditServiceVersionEndpointUseCase ---

    @Override
    public void setVersionEndpoint(String dnsName, String pathPrefix, String versionEndpoint,
                                   String versionProperty) {
        String normalisedPath = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        ReverseProxyRoute.validatePathPrefix(normalisedPath);
        requireNonMandatory(dnsName, "Cannot set version endpoint for built-in service: ");
        String endpoint = (versionEndpoint == null || versionEndpoint.isBlank()) ? null : versionEndpoint.trim();
        String property = (versionProperty == null || versionProperty.isBlank()) ? null : versionProperty.trim();
        log.info("Setting versionEndpoint={} versionProperty={} for {} ({})",
            endpoint, property, dnsName, normalisedPath);
        forPersistingReverseProxyRoutes.setRouteVersionEndpoint(dnsName, normalisedPath, endpoint, property);
        invalidatePublishedServicesCache();
    }

    // --- EditServiceRedirectUseCase ---

    @Override
    public void setRootRedirectPath(String dnsName, String pathPrefix, String rootRedirectPath) {
        String normalisedPath = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        ReverseProxyRoute.validatePathPrefix(normalisedPath);
        requireNonMandatory(dnsName, "Cannot edit built-in service: ");
        log.info("Setting root redirect path for {} ({}) to {}", dnsName, normalisedPath, rootRedirectPath);
        forPersistingReverseProxyRoutes.setRouteRootRedirectPath(dnsName, normalisedPath, rootRedirectPath);
        invalidatePublishedServicesCache();
    }

    private void requireNonMandatory(String dnsName, String errorPrefix) {
        if (PublishingConstants.isMandatory(dnsName, configResolver.getDomain())) {
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
