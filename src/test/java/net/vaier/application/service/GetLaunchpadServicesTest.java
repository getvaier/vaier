package net.vaier.application.service;

import net.vaier.application.GetLaunchpadServicesUseCase.LaunchpadServiceUco;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.*;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.LaunchpadVisibility;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForResolvingPeerNames;
import net.vaier.domain.port.ForResolvingServerLanCidr;
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

    @InjectMocks
    PublishingService service;

    @BeforeEach
    void setUp() {
        lenient().when(configResolver.getDomain()).thenReturn("example.com");
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        lenient().when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());
        lenient().when(forResolvingPeerNames.resolvePeerNameByIp(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
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

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dnsAddress()).isEqualTo("app.example.com");
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
    void getLaunchpadServices_authenticatedRoute_urlGoesViaAuthelia() {
        ReverseProxyRoute route = new ReverseProxyRoute(
            "route", "app.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null), null, null, null, null, false
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route));
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).url())
            .isEqualTo("https://login.example.com/?rd=https%3A%2F%2Fapp.example.com%2F");
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
    void getLaunchpadServices_pathBasedRoute_faviconQueryCarriesPathPrefix() {
        ReverseProxyRoute pathBased = new ReverseProxyRoute(
            "svc-grafana-router", "svc.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, "/grafana"
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(pathBased));
        setupDnsRecord("svc.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).faviconQuery()).isEqualTo("host=svc.example.com&pathPrefix=%2Fgrafana");
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
    void getLaunchpadServices_authProtectedPathRoute_autheliaReturnTargetHasNoTrailingSlash() {
        ReverseProxyRoute pathBased = new ReverseProxyRoute(
            "svc-grafana-router", "svc.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null),
            null, null, null, null, false, false, null, "/grafana"
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(pathBased));
        setupDnsRecord("svc.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null);

        assertThat(result.get(0).url())
            .isEqualTo("https://login.example.com/?rd=https%3A%2F%2Fsvc.example.com%2Fgrafana");
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

    // --- caller-authenticated gating (issue #207) ---

    @Test
    void getLaunchpadServices_authProtectedRoute_excludedWhenCallerAnonymous() {
        ReverseProxyRoute route = new ReverseProxyRoute(
            "route", "internal.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null), null, null, null, null, false
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route));
        setupDnsRecord("internal.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null, false);

        assertThat(result).isEmpty();
    }

    @Test
    void getLaunchpadServices_authProtectedRoute_includedWhenCallerAuthenticated() {
        ReverseProxyRoute route = new ReverseProxyRoute(
            "route", "internal.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null), null, null, null, null, false
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route));
        setupDnsRecord("internal.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null, true);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dnsAddress()).isEqualTo("internal.example.com");
    }

    @Test
    void getLaunchpadServices_publicRoute_includedWhenCallerAnonymous() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        List<LaunchpadServiceUco> result = service.getLaunchpadServices(null, false);

        assertThat(result).hasSize(1);
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
