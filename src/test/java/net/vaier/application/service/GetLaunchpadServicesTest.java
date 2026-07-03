package net.vaier.application.service;

import net.vaier.config.ServiceNames;
import net.vaier.domain.port.ForDiscoveringPeerContainers;
import net.vaier.domain.port.ForDiscoveringVaierServerContainers;
import net.vaier.domain.port.ForGettingLanServerScrape;
import net.vaier.application.GetLaunchpadServicesUseCase.LaunchpadServiceUco;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.*;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.LaunchpadVisibility;
import net.vaier.domain.port.ForCheckingLanReachability;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForProbingServiceVersion;
import net.vaier.domain.port.ForResolvingPeerNames;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import net.vaier.domain.port.ForResolvingServiceGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetLaunchpadServicesTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Mock
    ForGettingServerInfo forGettingServerInfo;

    @Mock
    ForPersistingDnsRecords forPersistingDnsRecords;

    @Mock
    ForGettingVpnClients forGettingVpnClients;

    @Mock
    ForResolvingPeerNames forResolvingPeerNames;

    @Mock
    ForGettingPeerConfigurations forGettingPeerConfigurations;

    @Mock
    ForResolvingServerLanCidr forResolvingServerLanCidr;

    @Mock
    ConfigResolver configResolver;

    @Mock
    ForDiscoveringPeerContainers discoverPeerContainers;

    @Mock
    ForDiscoveringVaierServerContainers discoverVaierServerContainers;

    @Mock
    ForGettingLanServerScrape getLanServerScrape;

    @Mock
    ForProbingServiceVersion forProbingServiceVersion;

    @Mock
    ForCheckingLanReachability forCheckingLanReachability;

    @Mock
    ForPersistingLanServers forPersistingLanServers;

    @Mock
    ForResolvingServiceGroup forResolvingServiceGroup;

    @InjectMocks
    PublishingService service;

    private AccessEntry admin() {
        return AccessEntry.builder().email("admin@example.com").role(Role.ADMIN).groups(List.of()).build();
    }

    private AccessEntry user(String... groups) {
        return AccessEntry.builder().email("u@example.com").role(Role.USER).groups(List.of(groups)).build();
    }

    private AccessEntry pending() {
        return AccessEntry.builder().email("p@example.com").role(Role.PENDING).groups(List.of()).build();
    }

    private ReverseProxyRoute socialRoute(String host, String address, int port) {
        return new ReverseProxyRoute("route", host, address, port, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null), null, null,
            List.of(ServiceNames.OAUTH2_SIGNIN_MIDDLEWARE, ServiceNames.OAUTH2_AUTHN_MIDDLEWARE,
                ServiceNames.VAIER_AUTHZ_MIDDLEWARE), null, false);
    }

    @BeforeEach
    void setUp() {
        lenient().when(configResolver.getDomain()).thenReturn("example.com");
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        lenient().when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());
        lenient().when(forResolvingPeerNames.resolvePeerNameByIp(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(discoverPeerContainers.discoverAll()).thenReturn(List.of());
        lenient().when(getLanServerScrape.getLanServerContainers()).thenReturn(List.of());
        lenient().when(forCheckingLanReachability.snapshot()).thenReturn(java.util.Map.of());
        lenient().when(forPersistingLanServers.getAll()).thenReturn(List.of());
    }

    @Test
    void getLaunchpadServices_returnsOnlyDnsAddressAndHostAddress() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dnsAddress()).isEqualTo("app.example.com");
        assertThat(result.get(0).hostAddress()).isEqualTo("10.0.0.1");
    }

    @Test
    void getLaunchpadServices_unreachableHost_visibilityIsVisibleInactive() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).visibility()).isEqualTo(LaunchpadVisibility.VISIBLE_INACTIVE);
    }

    @Test
    void getLaunchpadServices_runningLocalService_visibilityIsVisibleActive() {
        setupOneRoute("app.example.com", "my-container", 8080);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(
            List.of(new DockerService("id", "my-container", "image", "latest",
                List.of(new DockerService.PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of(), "running"))
        );

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).visibility()).isEqualTo(LaunchpadVisibility.VISIBLE_ACTIVE);
    }

    @Test
    void getLaunchpadServices_excludesServicesWithNonExistingDns() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result).isEmpty();
    }

    @Test
    void getLaunchpadServices_emptyRoutes_returnsEmpty() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        assertThat(service.getLaunchpadServices(null)).isEmpty();
    }

    @Test
    void getLaunchpadServices_excludesMandatoryInfrastructureRouters() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("vaier.example.com", "vaier", 8080),
            route("login.example.com", "authelia", 9091),
            route("app.example.com", "10.0.0.1", 8080)
        ));
        DnsZone zone = new DnsZone("example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of(
            new DnsRecord("vaier.example.com", DnsRecordType.CNAME, 300L, List.of()),
            new DnsRecord("login.example.com", DnsRecordType.CNAME, 300L, List.of()),
            new DnsRecord("app.example.com", DnsRecordType.CNAME, 300L, List.of())
        ));
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        // Authelia is decommissioned: only the Vaier console router is mandatory infrastructure,
        // so login.<domain> now surfaces as an ordinary launchpad tile alongside app.
        assertThat(result).extracting(LaunchpadServiceUco::dnsAddress)
            .containsExactlyInAnyOrder("login.example.com", "app.example.com");
    }

    @Test
    void getLaunchpadServices_excludesTheOauth2AndDexInfrastructureRouters() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("oauth2.example.com", "oauth2-proxy", 4180),
            route("dex.example.com", "dex", 5556),
            route("app.example.com", "10.0.0.1", 8080)
        ));
        DnsZone zone = new DnsZone("example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of(
            new DnsRecord("oauth2.example.com", DnsRecordType.CNAME, 300L, List.of()),
            new DnsRecord("dex.example.com", DnsRecordType.CNAME, 300L, List.of()),
            new DnsRecord("app.example.com", DnsRecordType.CNAME, 300L, List.of())
        ));
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        // oauth2 (sign-in gateway) and dex (OIDC broker) are Vaier infrastructure, so they never
        // appear as launchpad tiles — only the ordinary app does.
        assertThat(result).extracting(LaunchpadServiceUco::dnsAddress)
            .containsExactly("app.example.com");
    }

    @Test
    void getLaunchpadServices_multipleRoutes_returnsOnlyDnsOk() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("published.example.com", "10.0.0.1", 8080),
            route("pending.example.com", "10.0.0.2", 9090)
        ));
        DnsZone zone = new DnsZone("example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone))
            .thenReturn(List.of(new DnsRecord("published.example.com", DnsRecordType.CNAME, 300L, List.of())));
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dnsAddress()).isEqualTo("published.example.com");
    }

    @Test
    void getLaunchpadServices_callerIpMatchesPeerEndpoint_setsUrlToLanAddress() {
        setupOneRoute("app.example.com", "10.13.13.6", 6875);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            vpnClient("10.13.13.6/32", "51.175.8.217")
        ));
        setupEmptyLocalServices();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "", MachineType.UBUNTU_SERVER, null, "192.168.3.121")
        ));

        List<LaunchpadServiceUco> result = service.getLaunchpadServices("51.175.8.217");

        assertThat(result.get(0).url()).isEqualTo("http://192.168.3.121:6875");
    }

    @Test
    void getLaunchpadServices_callerIpDoesNotMatchAnyPeerEndpoint_noDirectUrl() {
        setupOneRoute("app.example.com", "10.13.13.6", 6875);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            vpnClient("10.13.13.6/32", "51.175.8.217")
        ));
        setupEmptyLocalServices();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "", MachineType.UBUNTU_SERVER, null, "192.168.3.121")
        ));

        List<LaunchpadServiceUco> result = service.getLaunchpadServices("203.0.113.10");

        assertThat(result.get(0).url()).isEqualTo("https://app.example.com");
    }

    @Test
    void getLaunchpadServices_peerHasNoLanAddress_noDirectUrl() {
        setupOneRoute("app.example.com", "10.13.13.6", 6875);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            vpnClient("10.13.13.6/32", "51.175.8.217")
        ));
        setupEmptyLocalServices();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "", MachineType.UBUNTU_SERVER, null, null)
        ));

        List<LaunchpadServiceUco> result = service.getLaunchpadServices("51.175.8.217");

        assertThat(result.get(0).url()).isEqualTo("https://app.example.com");
    }

    @Test
    void getLaunchpadServices_callerIpNull_noDirectUrl() {
        setupOneRoute("app.example.com", "10.13.13.6", 6875);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            vpnClient("10.13.13.6/32", "51.175.8.217")
        ));
        setupEmptyLocalServices();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "", MachineType.UBUNTU_SERVER, null, "192.168.3.121")
        ));

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).url()).isEqualTo("https://app.example.com");
    }

    @Test
    void getLaunchpadServices_routeAddressHasNoMatchingPeer_noDirectUrl() {
        setupOneRoute("app.example.com", "my-local-container", 8080);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            vpnClient("10.13.13.6/32", "51.175.8.217")
        ));
        setupEmptyLocalServices();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "", MachineType.UBUNTU_SERVER, null, "192.168.3.121")
        ));

        List<LaunchpadServiceUco> result = service.getLaunchpadServices("51.175.8.217");

        assertThat(result.get(0).url()).isEqualTo("https://app.example.com");
    }

    @Test
    void getLaunchpadServices_directUrlDisabledOnRoute_noDirectUrl() {
        ReverseProxyRoute route = new ReverseProxyRoute(
            "route", "app.example.com", "10.13.13.6", 6875, "svc",
            null, null, null, null, null, true
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route));
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            vpnClient("10.13.13.6/32", "51.175.8.217")
        ));
        setupEmptyLocalServices();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "", MachineType.UBUNTU_SERVER, null, "192.168.3.121")
        ));

        List<LaunchpadServiceUco> result = service.getLaunchpadServices("51.175.8.217");

        assertThat(result.get(0).url()).isEqualTo("https://app.example.com");
    }

    @Test
    void getLaunchpadServices_socialRoute_urlLinksDirectlyToTheHost() {
        ReverseProxyRoute route = new ReverseProxyRoute(
            "route", "app.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null), null, null,
            List.of(ServiceNames.OAUTH2_SIGNIN_MIDDLEWARE, ServiceNames.OAUTH2_AUTHN_MIDDLEWARE,
                ServiceNames.VAIER_AUTHZ_MIDDLEWARE), null, false
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route));
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).url())
            .isEqualTo("https://app.example.com");
    }

    @Test
    void getLaunchpadServices_publicRoute_urlIsDirectHttps() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).url()).isEqualTo("https://app.example.com");
    }

    @Test
    void getLaunchpadServices_authenticatedRouteOnSameLan_urlIsDirectLanBypass() {
        ReverseProxyRoute route = new ReverseProxyRoute(
            "route", "app.example.com", "10.13.13.6", 6875, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null), null, null, null, null, false
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route));
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            vpnClient("10.13.13.6/32", "51.175.8.217")
        ));
        setupEmptyLocalServices();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "", MachineType.UBUNTU_SERVER, null, "192.168.3.121")
        ));

        List<LaunchpadServiceUco> result = service.getLaunchpadServices("51.175.8.217");

        assertThat(result.get(0).url()).isEqualTo("http://192.168.3.121:6875");
    }

    @Test
    void getLaunchpadServices_pathBasedRoute_displayNameIsFinalPathSegment() {
        ReverseProxyRoute pathBased = new ReverseProxyRoute(
            "svc-grafana-router", "svc.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, "/grafana"
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(pathBased));
        setupDnsRecord("svc.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).displayName()).isEqualTo("grafana");
    }

    @Test
    void getLaunchpadServices_pathBasedRoute_iconQueryCarriesPathPrefix() {
        ReverseProxyRoute pathBased = new ReverseProxyRoute(
            "svc-grafana-router", "svc.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, "/grafana"
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(pathBased));
        setupDnsRecord("svc.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).iconQuery()).isEqualTo("host=svc.example.com&pathPrefix=%2Fgrafana");
    }

    @Test
    void getLaunchpadServices_publicPathRoute_urlHasNoTrailingSlash() {
        // Destination apps can serve different content for `/path/` vs `/path` (e.g. CorpoWebserver
        // returns its dashboard at /CorpoWebserver/stat but a different page at .../stat/). Let the
        // target redirect to its preferred shape itself instead of forcing a trailing slash.
        ReverseProxyRoute pathBased = new ReverseProxyRoute(
            "svc-grafana-router", "svc.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, "/grafana"
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(pathBased));
        setupDnsRecord("svc.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).url()).isEqualTo("https://svc.example.com/grafana");
    }

    @Test
    void getLaunchpadServices_socialPathRoute_linksDirectlyToThePathPrefix() {
        ReverseProxyRoute pathBased = new ReverseProxyRoute(
            "svc-grafana-router", "svc.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null),
            null, null, List.of(ServiceNames.OAUTH2_SIGNIN_MIDDLEWARE, ServiceNames.OAUTH2_AUTHN_MIDDLEWARE,
                ServiceNames.VAIER_AUTHZ_MIDDLEWARE), null, false, false, null, "/grafana"
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(pathBased));
        setupDnsRecord("svc.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).url())
            .isEqualTo("https://svc.example.com/grafana");
    }

    @Test
    void getLaunchpadServices_aliasSet_displayNameIsAlias() {
        ReverseProxyRoute aliased = new ReverseProxyRoute(
            "svc-grafana-router", "svc.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, "/grafana", false, "Grafana Prod"
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(aliased));
        setupDnsRecord("svc.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).displayName()).isEqualTo("Grafana Prod");
    }

    // --- viewer-adaptive gating (public, viewer-adaptive launchpad) ---

    @Test
    void getLaunchpadServices_anonymousViewer_seesOnlyPublicServices() {
        ReverseProxyRoute publicRoute = route("public.example.com", "10.0.0.1", 8080);
        ReverseProxyRoute social = socialRoute("internal.example.com", "10.0.0.2", 8080);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(publicRoute, social));
        DnsZone zone = new DnsZone("example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of(
            new DnsRecord("public.example.com", DnsRecordType.CNAME, 300L, List.of()),
            new DnsRecord("internal.example.com", DnsRecordType.CNAME, 300L, List.of())));
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null, (AccessEntry) null);

        assertThat(result).extracting(LaunchpadServiceUco::dnsAddress)
            .containsExactly("public.example.com");
    }

    @Test
    void getLaunchpadServices_anonymousViewer_neverReadsTheAccessStore() {
        setupOneRoute("public.example.com", "10.0.0.1", 8080);
        setupDnsRecord("public.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        service.getLaunchpadServices(null, (AccessEntry) null);

        verify(forResolvingServiceGroup, org.mockito.Mockito.never()).allowedGroupsForHost(any());
    }

    @Test
    void getLaunchpadServices_adminViewer_seesEveryPublicAndSocialService() {
        ReverseProxyRoute publicRoute = route("public.example.com", "10.0.0.1", 8080);
        ReverseProxyRoute social = socialRoute("internal.example.com", "10.0.0.2", 8080);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(publicRoute, social));
        DnsZone zone = new DnsZone("example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of(
            new DnsRecord("public.example.com", DnsRecordType.CNAME, 300L, List.of()),
            new DnsRecord("internal.example.com", DnsRecordType.CNAME, 300L, List.of())));
        setupEmptyVpnClients();
        setupEmptyLocalServices();
        lenient().when(forResolvingServiceGroup.allowedGroupsForHost("internal.example.com"))
            .thenReturn(List.of("devs"));

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null, admin());

        assertThat(result).extracting(LaunchpadServiceUco::dnsAddress)
            .containsExactlyInAnyOrder("public.example.com", "internal.example.com");
    }

    @Test
    void getLaunchpadServices_userViewer_seesSocialServicesInAnAllowedGroupAndHidesTheRest() {
        ReverseProxyRoute publicRoute = route("public.example.com", "10.0.0.1", 8080);
        ReverseProxyRoute reachable = socialRoute("git.example.com", "10.0.0.2", 8080);
        ReverseProxyRoute forbidden = socialRoute("plex.example.com", "10.0.0.3", 8080);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(publicRoute, reachable, forbidden));
        DnsZone zone = new DnsZone("example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of(
            new DnsRecord("public.example.com", DnsRecordType.CNAME, 300L, List.of()),
            new DnsRecord("git.example.com", DnsRecordType.CNAME, 300L, List.of()),
            new DnsRecord("plex.example.com", DnsRecordType.CNAME, 300L, List.of())));
        setupEmptyVpnClients();
        setupEmptyLocalServices();
        when(forResolvingServiceGroup.allowedGroupsForHost("git.example.com")).thenReturn(List.of("devs"));
        when(forResolvingServiceGroup.allowedGroupsForHost("plex.example.com")).thenReturn(List.of("family"));

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null, user("devs"));

        assertThat(result).extracting(LaunchpadServiceUco::dnsAddress)
            .containsExactlyInAnyOrder("public.example.com", "git.example.com");
    }

    @Test
    void getLaunchpadServices_pendingViewer_seesOnlyPublicServices() {
        ReverseProxyRoute publicRoute = route("public.example.com", "10.0.0.1", 8080);
        ReverseProxyRoute social = socialRoute("internal.example.com", "10.0.0.2", 8080);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(publicRoute, social));
        DnsZone zone = new DnsZone("example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of(
            new DnsRecord("public.example.com", DnsRecordType.CNAME, 300L, List.of()),
            new DnsRecord("internal.example.com", DnsRecordType.CNAME, 300L, List.of())));
        setupEmptyVpnClients();
        setupEmptyLocalServices();
        lenient().when(forResolvingServiceGroup.allowedGroupsForHost(any())).thenReturn(List.of());

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null, pending());

        assertThat(result).extracting(LaunchpadServiceUco::dnsAddress)
            .containsExactly("public.example.com");
    }

    @Test
    void getLaunchpadServices_hiddenFromLaunchpadRoute_excludedFromResult() {
        ReverseProxyRoute hidden = new ReverseProxyRoute(
            "internal-router", "internal-api.example.com", "10.0.0.5", 9000, "internal-svc",
            null, null, null, null, null, false, false, null, null, true
        );
        ReverseProxyRoute visible = route("app.example.com", "10.0.0.1", 8080);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(hidden, visible));
        DnsZone zone = new DnsZone("example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of(
            new DnsRecord("internal-api.example.com", DnsRecordType.CNAME, 300L, List.of()),
            new DnsRecord("app.example.com", DnsRecordType.CNAME, 300L, List.of())
        ));
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dnsAddress()).isEqualTo("app.example.com");
    }

    @Test
    void getLaunchpadServices_peerHasNoEndpoint_noDirectUrl() {
        setupOneRoute("app.example.com", "10.13.13.6", 6875);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            vpnClient("10.13.13.6/32", null)
        ));
        setupEmptyLocalServices();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "", MachineType.UBUNTU_SERVER, null, "192.168.3.121")
        ));

        List<LaunchpadServiceUco> result = service.getLaunchpadServices("51.175.8.217");

        assertThat(result.get(0).url()).isEqualTo("https://app.example.com");
    }

    // --- backing container image (issue #210) ---

    @Test
    void getLaunchpadServices_peerService_carriesBackingContainerImage() {
        setupOneRoute("app.example.com", "10.13.13.6", 6875);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of(
            new net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers("apalveien5", "10.13.13.6", "OK",
                List.of(new DockerService("id", "bookstack", "linuxserver/bookstack:24.05", "24.05",
                    List.of(new DockerService.PortMapping(80, 6875, "tcp", "0.0.0.0")), List.of(), "running")),
                false, "")));

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).image()).isEqualTo("linuxserver/bookstack:24.05");
        assertThat(result.get(0).version()).isEqualTo("24.05");
    }

    @Test
    void getLaunchpadServices_vaierServerService_carriesBackingContainerImage() {
        setupOneRoute("app.example.com", "grafana", 3000);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        when(discoverVaierServerContainers.discover()).thenReturn(
            List.of(new DockerService("id", "grafana", "grafana/grafana:11.3.0", "11.3.0",
                List.of(new DockerService.PortMapping(3000, 3000, "tcp", "0.0.0.0")), List.of(), "running")));

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).image()).isEqualTo("grafana/grafana:11.3.0");
        assertThat(result.get(0).version()).isEqualTo("11.3.0");
    }

    @Test
    void getLaunchpadServices_lanServicePublishedAsBareHostPort_hasNoImage() {
        ReverseProxyRoute lan = ReverseProxyRoute.lanRoute(
            "route", "nas.example.com", "192.168.3.50", 5000, "http", "svc");
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(lan));
        setupDnsRecord("nas.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).image()).isNull();
        assertThat(result.get(0).version()).isNull();
    }

    // --- version endpoint (issue #210 — LAN-native version) ---

    @Test
    void getLaunchpadServices_lanNativeServiceWithVersionEndpoint_carriesProbedVersion() {
        ReverseProxyRoute lan = new ReverseProxyRoute("route", "app.example.com", "192.168.3.50", 9000,
            "svc", null, null, null, null, null, false, true, "http", null, false, null,
            "/sys/metrics?name[]=system_info", "display");
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(lan));
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();
        when(forProbingServiceVersion.probeVersion(
                "http://192.168.3.50:9000/sys/metrics?name[]=system_info", "display"))
            .thenReturn(Optional.of("v.5.0.0.0_DEV#a0fdfff02ba"));

        service.refreshLaunchpadVersions();
        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).version()).isEqualTo("v.5.0.0.0_DEV#a0fdfff02ba");
        assertThat(result.get(0).image()).isNull();
    }

    @Test
    void getLaunchpadServices_versionEndpointOverridesBackingContainerVersion() {
        ReverseProxyRoute route = new ReverseProxyRoute("route", "app.example.com", "grafana", 3000,
            "svc", null, null, null, null, null, false, false, null, null, false, null,
            "/status", "build");
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route));
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        when(discoverVaierServerContainers.discover()).thenReturn(
            List.of(new DockerService("id", "grafana", "grafana/grafana:11.3.0", "11.3.0",
                List.of(new DockerService.PortMapping(3000, 3000, "tcp", "0.0.0.0")), List.of(), "running")));
        when(forProbingServiceVersion.probeVersion(any(), any())).thenReturn(Optional.of("custom-5.0"));

        service.refreshLaunchpadVersions();
        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).version()).isEqualTo("custom-5.0");
        assertThat(result.get(0).image()).isEqualTo("grafana/grafana:11.3.0");
    }

    @Test
    void getLaunchpadServices_readsCachedVersionsWithoutProbingOnTheReadPath() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(versionRoute()));
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();
        when(forProbingServiceVersion.probeVersion(any(), any())).thenReturn(Optional.of("1.0"));

        service.refreshLaunchpadVersions();
        service.getLaunchpadServices(null);
        service.getLaunchpadServices(null);

        // Probing happens once, in the background refresh — never on a launchpad read.
        verify(forProbingServiceVersion, times(1)).probeVersion(any(), any());
    }

    @Test
    void refreshLaunchpadVersions_reProbesEveryEndpointOnEachRun() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(versionRoute()));
        when(forProbingServiceVersion.probeVersion(any(), any())).thenReturn(Optional.of("1.0"));

        service.refreshLaunchpadVersions();
        service.refreshLaunchpadVersions();

        verify(forProbingServiceVersion, times(2)).probeVersion(any(), any());
    }

    private ReverseProxyRoute versionRoute() {
        return new ReverseProxyRoute("route", "app.example.com", "192.168.3.50", 9000, "svc", null,
            null, null, null, null, false, true, "http", null, false, null, "/sys/metrics", "display");
    }

    private VpnClient vpnClient(String allowedIps, String endpointIp) {
        return new VpnClient("pubkey", allowedIps, endpointIp, "51820", "1700000000", "0", "0");
    }

    // --- setup helpers ---

    private void setupOneRoute(String domain, String address, int port) {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(route(domain, address, port)));
    }

    private void setupDnsRecord(String name, DnsRecordType type) {
        DnsZone zone = new DnsZone("example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone))
            .thenReturn(List.of(new DnsRecord(name, type, 300L, List.of())));
    }

    private void setupEmptyVpnClients() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
    }

    private void setupEmptyLocalServices() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(List.of());
    }

    private ReverseProxyRoute route(String domain, String address, int port) {
        return new ReverseProxyRoute("route", domain, address, port, "svc", null);
    }
}
