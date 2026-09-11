package net.vaier.application.service;

import net.vaier.domain.TestMachineIds;
import net.vaier.domain.port.ForResolvingVaierServerIdentity;
import net.vaier.domain.port.ForDiscoveringLanServerContainers;
import net.vaier.domain.port.ForDiscoveringPeerContainers;
import net.vaier.domain.port.ForDiscoveringVaierServerContainers;
import net.vaier.domain.port.ForGettingLanServerScrape;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import net.vaier.domain.port.ForGettingVaierServerDockerServices;
import net.vaier.application.GetPublishedServicesUseCase.PublishedServiceUco;
import net.vaier.application.PublishPeerServiceUseCase.PendingPublication;
import net.vaier.application.PublishPeerServiceUseCase.PublishStatus;
import net.vaier.application.UpdatePublishedServiceUseCase.PublishedServicePatch;
import net.vaier.domain.PublishableService;
import net.vaier.domain.PublishableService.PublishableSource;
import net.vaier.config.ConfigResolver;
import net.vaier.config.ServiceNames;
import net.vaier.domain.*;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.Server.State;
import net.vaier.domain.port.ForCheckingLanReachability;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForManagingIgnoredServices;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForProbingServiceVersion;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForResolvingPeerIds;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import net.vaier.domain.port.ForResolvingServiceGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublishingServiceTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Mock
    ForGettingServerInfo forGettingServerInfo;

    @Mock
    ForGettingVpnClients forGettingVpnClients;

    @Mock
    ForResolvingPeerIds forResolvingPeerIds;

    @Mock
    ForGettingPeerConfigurations forGettingPeerConfigurations;

    @Mock
    ForResolvingServerLanCidr forResolvingServerLanCidr;

    @Mock
    ConfigResolver configResolver;

    @Mock
    ForPublishingEvents forPublishingEvents;

    @Mock
    ForManagingIgnoredServices forManagingIgnoredServices;

    @Mock
    PendingPublicationsService pendingPublicationsService;

    @Mock
    ForDiscoveringPeerContainers discoverPeerContainers;

    @Mock
    ForDiscoveringVaierServerContainers discoverVaierServerContainers;

    @Mock
    ForGettingLanServerScrape getLanServerScrape;

    @Mock
    ForGettingVaierServerDockerServices getVaierServerDockerServices;

    @Mock
    ForProbingServiceVersion forProbingServiceVersion;

    @Mock
    ForPersistingLanServers forPersistingLanServers;

    @Mock
    ForCheckingLanReachability forCheckingLanReachability;

    @Mock
    ForResolvingServiceGroup forResolvingServiceGroup;

    @Mock
    ForResolvingVaierServerIdentity vaierServerIdentity;

    @InjectMocks
    PublishingService service;

    @BeforeEach
    void setUp() {
        lenient().when(configResolver.getDomain()).thenReturn("example.com");
        // The Vaier server's identity: every published-service view attributes its route to a machine,
        // and a hub route belongs to the machine Vaier runs on.
        lenient().when(vaierServerIdentity.identity()).thenReturn(TestMachineIds.of("Vaier server"));
        // getPublishedServices() always resolves the server LAN CIDR; default it to "absent" so the
        // relay-only tests behave as before. (Mockito would return Optional.empty() anyway — this is
        // just explicit.) Tests that exercise the server-LAN-CIDR path override it.
        lenient().when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());
        // Every publish activates on the common pool. A test that never stubs the route in would otherwise
        // leave a 15 s poller behind, and enough of them starve the pool on a 4-core runner (CI went red
        // with parallelism 3 while the 2-core dev box, which runs a thread per task, stayed green).
        service.traefikActivationTimeoutMillis = 500;
        service.traefikActivationRetryIntervalMillis = 10;
    }

    @Test
    void getPublishedServices_emptyRoutes_returnsEmptyWithoutCallingOtherPorts() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        assertThat(service.getPublishedServices()).isEmpty();
        verifyNoInteractions(forGettingVpnClients, forGettingServerInfo);
    }

    @Test
    void getPublishedServices_surfacesTheRoutesAuthMode() {
        ReverseProxyRoute social = new ReverseProxyRoute("app-router", "app.example.com", "10.0.0.1", 8080,
            "svc", null, List.of("websecure"), null,
            List.of("oauth2-signin", "oauth2-authn", "vaier-authz", "vaier-errors"));
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(social));
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.authMode()).isEqualTo("social");
    }

    @Test
    void getPublishedServices_excludesTheOauth2HelperRouter() {
        ReverseProxyRoute primary = new ReverseProxyRoute("app-router", "app.example.com", "10.0.0.1", 8080,
            "svc", null, List.of("websecure"), null,
            List.of("oauth2-signin", "oauth2-authn", "vaier-authz", "vaier-errors"));
        ReverseProxyRoute helper = new ReverseProxyRoute("app-example-com-oauth2-router", "app.example.com",
            "oauth2-proxy", 4180, "oauth2-proxy-svc", null, List.of("websecure"), null, List.of());
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(primary, helper));
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        assertThat(service.getPublishedServices()).hasSize(1);
    }

    @Test
    void getPublishedServices_runningLocalService_hostStateOk() {
        setupOneRoute("app.example.com", "my-container", 8080);
        setupEmptyVpnClients();
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(
            List.of(new DockerService("id", "my-container", "image", "latest",
                List.of(new DockerService.PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of(), "running"))
        );

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.OK);
    }

    // --- image + version on the service card (#245) ---
    // Same enrichment as the launchpad: the route's backing container surfaces its image and
    // version; a configured version endpoint overrides the container's version (a service
    // running natively on a LAN host reports its own version over HTTP). LAN services published
    // as a bare host:port (no Docker container) have null image and version.

    @Test
    void getPublishedServices_routeBackedByVaierServerContainer_surfacesImageAndVersion() {
        setupOneRoute("app.example.com", "my-container", 8080);
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();
        when(discoverVaierServerContainers.discover()).thenReturn(List.of(
            new DockerService("id", "my-container", "grafana/grafana:11.3.0", "11.3.0",
                List.of(new PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of(), "running")
        ));

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.image()).isEqualTo("grafana/grafana:11.3.0");
        assertThat(result.version()).isEqualTo("11.3.0");
    }

    @Test
    void getPublishedServices_routeWithNoBackingContainer_imageAndVersionNull() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.image()).isNull();
        assertThat(result.version()).isNull();
    }

    @Test
    void getPublishedServices_routeWithVersionEndpoint_usesProbedVersionOverContainerVersion() {
        ReverseProxyRoute route = ReverseProxyRoute.builder().name("route").domainName("app.example.com")
            .address("my-container").port(8080).service("svc")
            .versionEndpoint("/api/health").versionProperty("version").build();
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route));
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();
        when(discoverVaierServerContainers.discover()).thenReturn(List.of(
            new DockerService("id", "my-container", "grafana/grafana:11.3.0", "11.3.0",
                List.of(new PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of(), "running")
        ));
        // launchpadVersions is populated by the periodic refresh; seed it directly.
        ReflectionTestUtils.setField(service, "launchpadVersions",
            Map.of("route", "2.0.0-probed"));

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.image()).isEqualTo("grafana/grafana:11.3.0");
        assertThat(result.version()).isEqualTo("2.0.0-probed");
    }

    @Test
    void getPublishedServices_stoppedLocalService_hostStateUnreachable() {
        setupOneRoute("app.example.com", "my-container", 8080);
        setupEmptyVpnClients();
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(
            List.of(new DockerService("id", "my-container", "image", "latest",
                List.of(new DockerService.PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of(), "exited"))
        );

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void getPublishedServices_lanServiceInsideServerLanCidr_reachabilityOk_hostStateOk() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            ReverseProxyRoute.lanRoute("box-router", "box.example.com", "172.31.5.20", 8080, "http", "box-svc")
        ));
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));
        when(forCheckingLanReachability.snapshot())
            .thenReturn(Map.of("172.31.5.20", Reachability.OK));

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.OK);
        assertThat(result.isLanService()).isTrue();
    }

    @Test
    void getPublishedServices_lanService_lanHostDown_hostStateUnreachable() {
        // Issue #208: a LAN service whose host is reachability-DOWN must report UNREACHABLE
        // even when the relay tunnel is up, so both the launchpad and the services UI can
        // surface the host as offline.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            ReverseProxyRoute.lanRoute("nas-router", "nas.example.com", "192.168.3.50", 5000, "http", "nas-svc")
        ));
        String recentHandshake = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        when(forGettingVpnClients.getClients()).thenReturn(
            List.of(new VpnClient("pubkey", "10.13.13.5/32", "1.2.3.4", "51820", recentHandshake, "0", "0"))
        );
        setupEmptyVaierServerServices();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new ForGettingPeerConfigurations.PeerConfiguration("apalveien5", "10.13.13.5", "",
                MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5")
        ));
        when(forCheckingLanReachability.snapshot())
            .thenReturn(Map.of("192.168.3.50", Reachability.DOWN));

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.UNREACHABLE);
        assertThat(result.isLanService()).isTrue();
    }

    @Test
    void getPublishedServices_lanService_lanHostOk_hostStateOk() {
        // An OK reachability lets the existing relay-only check decide; here the relay tunnel
        // is up, so the service is reported OK.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            ReverseProxyRoute.lanRoute("nas-router", "nas.example.com", "192.168.3.50", 5000, "http", "nas-svc")
        ));
        String recentHandshake = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        when(forGettingVpnClients.getClients()).thenReturn(
            List.of(new VpnClient("pubkey", "10.13.13.5/32", "1.2.3.4", "51820", recentHandshake, "0", "0"))
        );
        setupEmptyVaierServerServices();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new ForGettingPeerConfigurations.PeerConfiguration("apalveien5", "10.13.13.5", "",
                MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5")
        ));
        when(forCheckingLanReachability.snapshot())
            .thenReturn(Map.of("192.168.3.50", Reachability.OK));

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.OK);
    }

    @Test
    void getPublishedServices_lanService_lanHostNotProbed_hostStateUnknown() {
        // Issue #208: a never-probed LAN host must surface as UNKNOWN, not OK — otherwise the
        // services-card icon shows green on a machine we have no signal from.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            ReverseProxyRoute.lanRoute("nas-router", "nas.example.com", "192.168.3.50", 5000, "http", "nas-svc")
        ));
        String recentHandshake = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        when(forGettingVpnClients.getClients()).thenReturn(
            List.of(new VpnClient("pubkey", "10.13.13.5/32", "1.2.3.4", "51820", recentHandshake, "0", "0"))
        );
        setupEmptyVaierServerServices();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new ForGettingPeerConfigurations.PeerConfiguration("apalveien5", "10.13.13.5", "",
                MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5")
        ));
        when(forCheckingLanReachability.snapshot()).thenReturn(Map.of());

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void getPublishedServices_vpnPeerWithRecentHandshake_hostStateOk() {
        String recentHandshake = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        setupOneRoute("app.example.com", "10.13.13.2", 8080);
        when(forGettingVpnClients.getClients()).thenReturn(
            List.of(new VpnClient("pubkey", "10.13.13.2/32", "1.2.3.4", "51820", recentHandshake, "0", "0"))
        );
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        setupEmptyVaierServerServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.OK);
    }

    @Test
    void getPublishedServices_vpnPeerWithStaleHandshake_hostStateUnreachable() {
        setupOneRoute("app.example.com", "10.13.13.2", 8080);
        when(forGettingVpnClients.getClients()).thenReturn(
            List.of(new VpnClient("pubkey", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0"))
        );
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        setupEmptyVaierServerServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void getPublishedServices_noLocalServiceAndNoVpnClientMatch_hostStateUnreachable() {
        setupOneRoute("app.example.com", "192.168.99.1", 8080);
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void getPublishedServices_routeWithAuth_authenticatedIsTrue() {
        ReverseProxyRoute route = new ReverseProxyRoute(
            "route", "app.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", "admin", null)
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route));
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        assertThat(service.getPublishedServices().get(0).authenticated()).isTrue();
    }

    @Test
    void getPublishedServices_routeWithNoAuth_authenticatedIsFalse() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        assertThat(service.getPublishedServices().get(0).authenticated()).isFalse();
    }

    @Test
    void getPublishedServices_vaierInfrastructureRouter_isExcluded() {
        setupOneRoute(ServiceNames.VAIER + ".example.com", "10.0.0.1", 8080);
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        assertThat(service.getPublishedServices()).isEmpty();
    }

    @Test
    void getPublishedServices_decommissionedLoginRouter_isNoLongerExcluded() {
        // Authelia is decommissioned; login.<domain> is no longer mandatory infrastructure, so a
        // leftover login route is now surfaced as an ordinary published service.
        setupOneRoute("login.example.com", "10.0.0.1", 8080);
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        assertThat(service.getPublishedServices()).hasSize(1);
        assertThat(service.getPublishedServices().get(0).dnsAddress()).isEqualTo("login.example.com");
    }

    @Test
    void getPublishedServices_excludesOnlyTheVaierInfraRouter() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route(ServiceNames.VAIER + ".example.com", "vaier", 8080),
            route("login.example.com", "authelia", 9091),
            route("app.example.com", "10.0.0.1", 8080)
        ));
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        // Only the Vaier console is mandatory infrastructure now; login + app are ordinary services.
        assertThat(service.getPublishedServices()).extracting("dnsAddress")
            .containsExactlyInAnyOrder("login.example.com", "app.example.com");
    }

    @Test
    void getPublishedServices_expensivePortsCalledExactlyOnce() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("app.example.com", "10.0.0.1", 8080),
            route("db.example.com", "10.0.0.2", 5432)
        ));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.getPublishedServices();

        verify(forGettingVpnClients, times(1)).getClients();
        verify(forGettingServerInfo, times(1)).getServicesWithExposedPorts(any());
    }

    @Test
    void getPublishedServices_secondCall_returnsCachedResultWithoutRefetching() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("app.example.com", "10.0.0.1", 8080)
        ));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.getPublishedServices();
        service.getPublishedServices();

        verify(forPersistingReverseProxyRoutes, times(1)).getReverseProxyRoutes();
        verify(forGettingVpnClients, times(1)).getClients();
        verify(forGettingServerInfo, times(1)).getServicesWithExposedPorts(any());
    }

    @Test
    void getPublishedServices_afterInvalidation_refetchesFromPorts() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("app.example.com", "10.0.0.1", 8080)
        ));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.getPublishedServices();
        service.invalidatePublishedServicesCache();
        service.getPublishedServices();

        verify(forGettingVpnClients, times(2)).getClients();
        verify(forGettingServerInfo, times(2)).getServicesWithExposedPorts(any());
    }

    @Test
    void getPublishedServices_vaierServerService_nameIsSubdomainAtVaierServer() {
        setupOneRoute("pihole.example.com", "pihole", 8080);
        setupEmptyVpnClients();
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(
            List.of(new DockerService("id", "pihole", "image", "latest",
                List.of(new DockerService.PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of(), "running"))
        );

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.name()).isEqualTo("pihole @ Vaier server");
    }

    @Test
    void getPublishedServices_peerService_nameIsSubdomainAtPeerName() {
        String recentHandshake = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        setupOneRoute("pihole.myserver.example.com", "10.13.13.2", 8080);
        when(forGettingVpnClients.getClients()).thenReturn(
            List.of(new VpnClient("pubkey", "10.13.13.2/32", "1.2.3.4", "51820", recentHandshake, "0", "0"))
        );
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("myserver");
        setupEmptyVaierServerServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.name()).isEqualTo("pihole @ myserver");
    }

    @Test
    void getPublishedServices_unknownIpAddress_nameShowsVaierServer() {
        setupOneRoute("app.example.com", "10.13.13.5", 8080);
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.name()).isEqualTo("app @ Vaier server");
    }

    @Test
    void getPublishedServices_simpleSubdomain_extractedCorrectly() {
        setupOneRoute("traefik.example.com", "traefik", 8080);
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.name()).isEqualTo("traefik @ Vaier server");
    }

    @Test
    void getPublishedServices_peerServiceOnSamePortAsLocal_resolvesToPeerNotLocal() {
        String recentHandshake = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        setupOneRoute("openhab.myserver.example.com", "10.13.13.3", 8080);
        when(forGettingVpnClients.getClients()).thenReturn(
            List.of(new VpnClient("pubkey", "10.13.13.3/32", "1.2.3.4", "51820", recentHandshake, "0", "0"))
        );
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.3")).thenReturn("myserver");
        // Local traefik also listens on port 8080
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(
            List.of(new DockerService("id", "traefik", "image", "latest",
                List.of(new DockerService.PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of(), "running"))
        );

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.name()).isEqualTo("openhab @ myserver");
    }

    // --- publishService / getPublishStatus / getPendingPublications ---
    // #331: publishing is one step, and that step is Traefik.

    /**
     * The headline contract of issue #331. Vaier supports wildcard DNS only: the operator's single
     * {@code *.<domain>} record already answers for every published name, so publishing a peer
     * service writes the Traefik route and nothing else. There is no record to create, nothing to
     * poll for propagation, and no {@code publish-dns-*} event — the route goes live as soon as
     * Traefik picks it up. The absence of any DNS port on {@link PublishingService}'s constructor
     * is the structural half of this; the observable half is asserted here.
     */
    @Test
    void publishingAPeerService_yieldsALiveTraefikRouteWithNoDnsStepAndNoPropagationWait() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of())                                    // conflict check: nothing on the fqdn
            .thenReturn(List.of(routeWithDomain("app.example.com")));  // Traefik has it on the first poll

        service.publishService("10.0.0.1", 8080, "app", false, null, false);

        verify(forPersistingReverseProxyRoutes, timeout(5_000))
            .addReverseProxyRoute("app.example.com", "10.0.0.1", 8080, false, null, null);
        verify(forPublishingEvents, timeout(5_000))
            .publish("published-services", "publish-traefik-active", "app");
        // No propagation delay: the conflict check plus a single Traefik poll is the whole flow.
        verify(forPersistingReverseProxyRoutes, times(2)).getReverseProxyRoutes();
        verify(forPublishingEvents, never())
            .publish(any(), argThat(event -> event.startsWith("publish-dns")), any());
    }

    /** Same #331 contract for the LAN flow — a LAN publish is equally one Traefik write. */
    @Test
    void publishingALanService_yieldsALiveTraefikRouteWithNoDnsStepAndNoPropagationWait() {
        LanServer nasBox = new LanServer("nas-box", "192.168.3.50", false, null);
        when(forPersistingLanServers.getAll()).thenReturn(List.of(nasBox));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.5", "",
                MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5")
        ));
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of())
            .thenReturn(List.of(routeWithDomain("nas.example.com")));

        service.publishLanService("nas", nasBox.machineId(), 5000, "https", false, false, null, null);

        verify(forPersistingReverseProxyRoutes, timeout(5_000)).addLanReverseProxyRoute(
            "nas.example.com", "192.168.3.50", 5000, "https", false, false, null, null);
        verify(forPublishingEvents, timeout(5_000))
            .publish("published-services", "publish-traefik-active", "nas");
        verify(forPersistingReverseProxyRoutes, times(2)).getReverseProxyRoutes();
        verify(forPublishingEvents, never())
            .publish(any(), argThat(event -> event.startsWith("publish-dns")), any());
    }

    // --- pathPrefix-aware publish (Phase B) ---

    @Test
    void publishService_pathPrefix_passesPathThroughToTraefik() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("bmp.example.com")));

        service.activate("bmp", "bmp.example.com", "10.13.13.6", 8081, false, null, false, "/auth");

        verify(forPersistingReverseProxyRoutes).addReverseProxyRoute(
            "bmp.example.com", "10.13.13.6", 8081, false, null, "/auth");
    }

    @Test
    void publishService_duplicateFqdnAndPath_throws() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(new ReverseProxyRoute("r", "bmp.example.com", "10.0.0.1", 8080, "svc",
                null, null, null, null, null, false, false, null, "/auth")));

        assertThrows(IllegalArgumentException.class, () ->
            service.publishService("10.13.13.6", 8081, "bmp", false, null, false, "/auth"));
    }

    @Test
    void deleteService_siblingRoutesRemain_removesOnlyTheTargetedRoute() {
        ReverseProxyRoute auth = new ReverseProxyRoute("auth", "bmp.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, "/auth");
        ReverseProxyRoute corpo = new ReverseProxyRoute("corpo", "bmp.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, "/CorpoWebserver");
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(auth, corpo))   // findByFqdnAndPath
            .thenReturn(List.of(corpo))         // wait-for-traefik-deletion, path-aware: /auth already absent
            .thenReturn(List.of(corpo));

        service.deleteService("bmp.example.com", "/auth");

        verify(forPersistingReverseProxyRoutes).deleteReverseProxyRoute("auth");
        verify(forPersistingReverseProxyRoutes, never()).deleteReverseProxyRoute("corpo");
    }

    @Test
    void getPublishStatus_routeExistsInTraefik_isTraefikActive() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            routeWithDomain("app.example.com")
        ));

        PublishStatus status = service.getPublishStatus("app");

        assertThat(status.traefikActive()).isTrue();
    }

    @Test
    void getPublishStatus_notInPendingNotInRoutes_isNotTraefikActive() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        PublishStatus status = service.getPublishStatus("app");

        assertThat(status.traefikActive()).isFalse();
    }

    @Test
    void getPublishStatus_afterPublish_inPendingReturnsPendingStatus() {
        // publishService adds to the pending map synchronously; Traefik pickup runs async.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        service.publishService("10.0.0.1", 8080, "app", false, null, false);

        // Immediately after publish the route isn't in Traefik yet -> still pending, not active.
        PublishStatus status = service.getPublishStatus("app");

        assertThat(status.traefikActive()).isFalse();
    }

    @Test
    void getPublishStatus_traefikRouteFound_removesPendingEntry() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            routeWithDomain("app.example.com")
        ));

        // First call clears from pending
        service.getPublishStatus("app");
        // Second call should still return true/true (from routes, not pending)
        PublishStatus status = service.getPublishStatus("app");

        assertThat(status.traefikActive()).isTrue();
    }

    @Test
    void getPendingPublications_afterPublish_returnsEntry() {
        service.publishService("10.0.0.1", 8080, "app", true, null, false);
        List<PendingPublication> pending = service.getPendingPublications();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).subdomain()).isEqualTo("app");
        assertThat(pending.get(0).requiresAuth()).isTrue();
    }

    // --- activate ---

    @Test
    void activate_waitsForTraefikRouteBeforeFiringEvent() {
        // First getReverseProxyRoutes call: Traefik not yet loaded; second call: route present
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of())
            .thenReturn(List.of(routeWithDomain("app.example.com")));

        service.activate("app", "app.example.com", "10.0.0.1", 8080, false, null, false, null);

        InOrder inOrder = inOrder(forPersistingReverseProxyRoutes, forPublishingEvents);
        inOrder.verify(forPersistingReverseProxyRoutes).addReverseProxyRoute("app.example.com", "10.0.0.1", 8080, false, null, null);
        inOrder.verify(forPublishingEvents).publish("published-services", "publish-traefik-active", "app");
        verify(forPersistingReverseProxyRoutes, atLeast(2)).getReverseProxyRoutes();
    }

    @Test
    void activate_invalidatesPublishedServicesCacheAfterActivation() {
        // PublishingService is now its own cache invalidator. Verify observable behaviour:
        // getPublishedServices called before and after activate re-fetches from ports.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        // Prime cache
        service.getPublishedServices();

        service.activate("app", "app.example.com", "10.0.0.1", 8080, false, null, false, null);

        // Cache should have been invalidated -> next call re-fetches
        service.getPublishedServices();

        verify(forGettingVpnClients, times(2)).getClients();
    }

    @Test
    void activate_directUrlDisabledTrue_persistsFlagAfterRouteCreated() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));

        service.activate("app", "app.example.com", "10.0.0.1", 8080, false, null, true, null);

        InOrder inOrder = inOrder(forPersistingReverseProxyRoutes);
        inOrder.verify(forPersistingReverseProxyRoutes).addReverseProxyRoute("app.example.com", "10.0.0.1", 8080, false, null, null);
        inOrder.verify(forPersistingReverseProxyRoutes).setRouteDirectUrlDisabled("app.example.com", null, true);
    }

    @Test
    void activate_directUrlDisabledFalse_doesNotPersistFlag() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));

        service.activate("app", "app.example.com", "10.0.0.1", 8080, false, null, false, null);

        verify(forPersistingReverseProxyRoutes, never()).setRouteDirectUrlDisabled(anyString(), any(), anyBoolean());
    }

    @Test
    void activate_traefikAddThrows_emitsRollback() {
        doThrow(new RuntimeException("traefik boom")).when(forPersistingReverseProxyRoutes)
            .addReverseProxyRoute(anyString(), anyString(), anyInt(), anyBoolean(), any(), any());

        service.activate("app", "app.example.com", "10.0.0.1", 8080, false, null, false, null);

        verify(forPublishingEvents).publish("published-services", "publish-rolled-back", "app");
    }

    @Test
    void activate_traefikNeverPicksUpRoute_deletesRouteAndEmitsRollback() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "traefikActivationTimeoutMillis", 50L);
        ReflectionTestUtils.setField(service, "traefikActivationRetryIntervalMillis", 10L);

        service.activate("app", "app.example.com", "10.0.0.1", 8080, false, null, false, null);

        verify(forPersistingReverseProxyRoutes).deleteReverseProxyRouteByDnsName("app.example.com");
        verify(forPublishingEvents).publish("published-services", "publish-rolled-back", "app");
    }

    @Test
    void activate_happyPath_doesNotEmitRollback() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));

        service.activate("app", "app.example.com", "10.0.0.1", 8080, false, null, false, null);

        verify(forPublishingEvents, never()).publish(anyString(), eq("publish-rolled-back"), anyString());
    }

    @Test
    void activate_addressMatchesLocalContainerIp_normalizesToContainerName() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));
        when(forGettingServerInfo.findContainerNameByIp(any(Server.class), eq("172.20.0.3")))
            .thenReturn(Optional.of("vaier"));

        service.activate("app", "app.example.com", "172.20.0.3", 8080, false, null, false, null);

        verify(forPersistingReverseProxyRoutes).addReverseProxyRoute(
            "app.example.com", "vaier", 8080, false, null, null);
    }

    @Test
    void activate_addressIsNotALocalContainerIp_passesAddressThroughUnchanged() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));
        when(forGettingServerInfo.findContainerNameByIp(any(Server.class), eq("10.13.13.3")))
            .thenReturn(Optional.empty());

        service.activate("app", "app.example.com", "10.13.13.3", 8080, false, null, false, null);

        verify(forPersistingReverseProxyRoutes).addReverseProxyRoute(
            "app.example.com", "10.13.13.3", 8080, false, null, null);
    }

    @Test
    void publishService_addressInsideRelayLanCidr_dispatchesToLanFlow() {
        // Discovered LAN docker services hit the regular /publish endpoint with a LAN IP. They must be
        // recognised and routed through the LAN flow instead — otherwise the resulting route gets
        // isLanService=false and the dashboard's directUrl() can't find the relay peer (#180).
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration(
                "apalveien5", "10.13.13.5", "", MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5")
        ));

        service.publishService("192.168.3.50", 80, "pihole", false, null, false);

        // The peer flow tracks pendingPublications by (address, port); the LAN flow does not, so
        // skipping it is one observable signal that the LAN dispatch happened. The other is that
        // the route lands through the LAN writer, which marks it isLanService=true.
        verify(forPersistingReverseProxyRoutes, timeout(5_000)).addLanReverseProxyRoute(
            eq("pihole.example.com"), eq("192.168.3.50"), eq(80), eq("http"),
            eq(false), eq(false), any(), any());
        verify(pendingPublicationsService, never()).track(anyString(), anyInt());
    }

    @Test
    void publishService_addressOutsideAllRelayLanCidrs_usesPeerFlow() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration(
                "apalveien5", "10.13.13.5", "", MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5")
        ));

        service.publishService("10.13.13.5", 8080, "app", false, null, false);

        verify(pendingPublicationsService).track("10.13.13.5", 8080);
    }

    @Test
    void publishService_addressInsideServerLanCidr_dispatchesToLanFlow() {
        // A box in the Vaier server's own subnet — no relay peer needed; still routed through the
        // LAN flow so the route gets isLanService=true.
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));

        service.publishService("172.31.5.20", 80, "pihole", false, null, false);

        verify(forPersistingReverseProxyRoutes, timeout(5_000)).addLanReverseProxyRoute(
            eq("pihole.example.com"), eq("172.31.5.20"), eq(80), eq("http"),
            eq(false), eq(false), any(), any());
        verify(pendingPublicationsService, never()).track(anyString(), anyInt());
    }

    // --- publishLanService (#175) ---

    @Test
    void publishLanService_unknownIdentity_throws() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () ->
            service.publishLanService("nas", MachineId.generate(), 5000, "http", false, false, null, null)
        );
        verify(forPersistingReverseProxyRoutes, never()).addLanReverseProxyRoute(
            anyString(), anyString(), anyInt(), anyString(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void publishLanService_targetIpOutsideEveryRelayLanCidr_throws() {
        LanServer offsite = new LanServer("offsite", "10.99.99.99", false, null);
        when(forPersistingLanServers.getAll()).thenReturn(List.of(offsite));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration(
                "apalveien5", "10.13.13.5", "", MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5")
        ));

        assertThrows(IllegalArgumentException.class, () ->
            service.publishLanService("nas", offsite.machineId(), 5000, "http", false, false, null, null)
        );
        verify(forPersistingReverseProxyRoutes, never()).addLanReverseProxyRoute(
            anyString(), anyString(), anyInt(), anyString(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void publishLanService_targetIpInsideRelayLanCidr_writesLanTraefikRoute() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("nas.example.com")));

        service.activateLan(
            "nas", "nas.example.com", "192.168.3.50", 5000, "https", true, true, null, null);

        verify(forPersistingReverseProxyRoutes).addLanReverseProxyRoute(
            "nas.example.com", "192.168.3.50", 5000, "https", true, true, null, null);
    }

    @Test
    void publishLanService_targetIpInsideServerLanCidr_writesLanTraefikRoute() {
        LanServer serverBox = new LanServer("server-box", "172.31.5.20", false, null);
        when(forPersistingLanServers.getAll()).thenReturn(List.of(serverBox));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));

        service.publishLanService("box", serverBox.machineId(), 8080, "http", false, false, null, null);

        verify(forPersistingReverseProxyRoutes, timeout(5_000)).addLanReverseProxyRoute(
            eq("box.example.com"), eq("172.31.5.20"), eq(8080), eq("http"),
            eq(false), eq(false), any(), any());
    }

    @Test
    void publishLanService_targetIpOutsideRelaysAndServerLanCidr_throws() {
        LanServer offsite = new LanServer("offsite", "10.99.99.99", false, null);
        when(forPersistingLanServers.getAll()).thenReturn(List.of(offsite));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));

        assertThrows(IllegalArgumentException.class, () ->
            service.publishLanService("box", offsite.machineId(), 8080, "http", false, false, null, null));
        verify(forPersistingReverseProxyRoutes, never()).addLanReverseProxyRoute(
            anyString(), anyString(), anyInt(), anyString(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void publishLanService_writesRedirectMiddlewareWhenRootRedirectPathProvided() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));

        service.activateLan(
            "app", "app.example.com", "192.168.3.50", 3000, "http", false, false, "/builder/ui/", null);

        verify(forPersistingReverseProxyRoutes).addLanReverseProxyRoute(
            "app.example.com", "192.168.3.50", 3000, "http", false, false, "/builder/ui/", null);
    }

    // --- deleteService ---

    @Test
    void deleteService_deletesTraefikRouteByFqdn() {
        service.deleteService("app.example.com");

        verify(forPersistingReverseProxyRoutes).deleteReverseProxyRouteByDnsName("app.example.com");
    }

    @Test
    void deleteService_rejectsVaierService() {
        assertThrows(IllegalArgumentException.class, () -> service.deleteService("vaier.example.com"));

        verifyNoInteractions(forPersistingReverseProxyRoutes);
    }

    @Test
    void deleteService_waitsForTraefikRouteToDisappearBeforeReturning() {
        // route present -> empty (transitional) -> empty (stable): needs 2 consecutive absences
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(new ReverseProxyRoute("r", "app.example.com", "10.0.0.1", 8080, "svc", null)))
            .thenReturn(List.of());

        service.deleteService("app.example.com");

        InOrder order = inOrder(forPersistingReverseProxyRoutes);
        order.verify(forPersistingReverseProxyRoutes).deleteReverseProxyRouteByDnsName("app.example.com");
        order.verify(forPersistingReverseProxyRoutes, atLeast(3)).getReverseProxyRoutes();
    }

    @Test
    void deleteService_requiresTwoConsecutiveAbsentChecks() {
        // Simulates Traefik briefly returning empty during reload, then empty again (stable)
        // A single empty reading is not enough — other routes could temporarily disappear too
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(new ReverseProxyRoute("r", "app.example.com", "10.0.0.1", 8080, "svc", null)))
            .thenReturn(List.of(new ReverseProxyRoute("r", "app.example.com", "10.0.0.1", 8080, "svc", null))) // still present after first empty check
            .thenReturn(List.of())  // first absence
            .thenReturn(List.of()); // second absence -> stable

        service.deleteService("app.example.com");

        verify(forPersistingReverseProxyRoutes, atLeast(4)).getReverseProxyRoutes();
    }

    @Test
    void deleteService_invalidatesPublishedServicesCache() {
        // PublishingService is its own cache invalidator. Verify observable behaviour:
        // cache re-fetches from ports after deleteService completes.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("other.example.com", "10.0.0.2", 9090)
        ));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.getPublishedServices(); // prime cache
        service.deleteService("app.example.com");
        service.getPublishedServices(); // should refetch

        verify(forGettingVpnClients, times(2)).getClients();
    }

    // --- getPublishableServices ---

    @Test
    void getPublishableServices_allPeersUnreachable_returnsOnlyLocalServices() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of(
            unreachablePeer("alice", "10.13.13.2")
        ));
        PublishableService vsSvc = vaierServerService("my-app", 3000);
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of(vsSvc));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).containsExactly(vsSvc);
    }

    @Test
    void getPublishableServices_peerWithTcpPortNotPublished_included() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainer("my-app", 8080, "tcp")))
        ));
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of());

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).address()).isEqualTo("10.13.13.2");
        assertThat(result.get(0).port()).isEqualTo(8080);
        assertThat(result.get(0).source()).isEqualTo(PublishableSource.PEER);
    }

    @Test
    void getPublishableServices_aStoppedContainerIsNotOffered_onAPeerOrALanServer() {
        // #356 made stopped containers visible to the scrape at last (they keep their published bindings),
        // and that must not turn into an offer to publish one. Nothing answers on that port.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(stoppedContainer("my-app", 8080)))
        ));
        when(getLanServerScrape.getLanServerContainers()).thenReturn(List.of(
            okLanHost("nas", "192.168.3.50", 2375, "apalveien5", List.of(stoppedContainer("webtrees", 8081)))
        ));
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_peerPortAlreadyPublished_excluded() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            routeForPublishable("10.13.13.2", 8080)
        ));
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainer("my-app", 8080, "tcp")))
        ));
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_peerWithExposedPortRange_excluded() {
        // #189 — a container that exposes a contiguous range (e.g. RoonServer 9100-9339)
        // surfaces as a single range PortMapping; it must NOT explode the publishable list.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        DockerService roon = new DockerService("id", "roonserver", "image", "latest",
            List.of(new PortMapping(9100, 9339, 9100, "tcp", "0.0.0.0")), List.of(), "running");
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of(
            okPeer("media", "10.13.13.5", List.of(roon))
        ));
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_peerWithUdpPort_excluded() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainer("dns-server", 53, "udp")))
        ));
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_mergesPeerAndLocalServices() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainer("peer-app", 9000, "tcp")))
        ));
        PublishableService vsSvc = vaierServerService("local-app", 3000);
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of(vsSvc));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PublishableService::source)
            .containsExactlyInAnyOrder(PublishableSource.PEER, PublishableSource.VAIER_SERVER);
    }

    @Test
    void getPublishableServices_duplicates_deduplicated() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of());
        PublishableService svc = vaierServerService("my-app", 3000);
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any()))
            .thenReturn(List.of(svc, svc));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
    }

    @Test
    void getPublishableServices_noPeersNoLocal_returnsEmpty() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of());
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_pendingPublication_excluded() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainer("my-app", 8080, "tcp")))
        ));
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of());
        when(pendingPublicationsService.isPending("10.13.13.2", 8080)).thenReturn(true);
        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_peerPortWithNullPublicPort_excluded() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainerWithNullPublicPort("my-app", 8080, "tcp")))
        ));
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_ignoredLocalService_markedIgnored() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of());
        PublishableService svc = vaierServerService("boring-app", 3000);
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of(svc));
        when(forManagingIgnoredServices.getIgnoredServiceKeys()).thenReturn(Set.of("boring-app:3000"));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ignored()).isTrue();
    }

    @Test
    void getPublishableServices_ignoredPeerService_markedIgnored() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainer("peer-app", 8080, "tcp")))
        ));
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of());
        when(forManagingIgnoredServices.getIgnoredServiceKeys()).thenReturn(Set.of("alice/peer-app:8080"));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ignored()).isTrue();
    }

    @Test
    void getPublishableServices_lanDockerHostContainer_sourceLabelIsRelayPeerName() {
        // For an already-published LAN docker route, ReverseProxyRoute.displayName() resolves the
        // server suffix to the relay peer name (the peer whose lanCidr contains the host IP).
        // The publishable list must use the same name so the suggested subdomain (`container.source`)
        // collapses cleanly to `container @ relayPeer` after publication, instead of leaking the
        // LAN host name into the displayed subdomain.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of());
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of());
        when(getLanServerScrape.getLanServerContainers()).thenReturn(List.of(
            okLanHost("nas", "192.168.1.50", 2375, "apalveien",
                List.of(peerContainer("pihole", 80, "tcp")))
        ));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
        PublishableService svc = result.getFirst();
        assertThat(svc.source()).isEqualTo(PublishableSource.LAN_SERVER);
        assertThat(svc.peerId()).isEqualTo("apalveien");
        assertThat(svc.address()).isEqualTo("192.168.1.50");
        assertThat(svc.containerName()).isEqualTo("pihole");
        assertThat(svc.port()).isEqualTo(80);
    }

    @Test
    void getPublishableServices_ignoredLanDockerHostService_markedIgnored() {
        // Ignore key for LAN_SERVER uses the LAN host IP (not the relay peer name) so two
        // LAN hosts behind the same relay with same-named containers don't collide.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of());
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of());
        when(getLanServerScrape.getLanServerContainers()).thenReturn(List.of(
            okLanHost("nas", "192.168.1.50", 2375, "apalveien",
                List.of(peerContainer("pihole", 80, "tcp")))
        ));
        when(forManagingIgnoredServices.getIgnoredServiceKeys()).thenReturn(Set.of("192.168.1.50/pihole:80"));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ignored()).isTrue();
    }

    @Test
    void getPublishableServices_nonIgnoredService_markedNotIgnored() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainers.discoverAll()).thenReturn(List.of());
        PublishableService svc = vaierServerService("useful-app", 3000);
        when(getVaierServerDockerServices.getUnpublishedVaierServerServices(any())).thenReturn(List.of(svc));
        when(forManagingIgnoredServices.getIgnoredServiceKeys()).thenReturn(Set.of());

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ignored()).isFalse();
    }

    // --- updateService ---

    @Test
    void updateService_requiresAuth_delegatesToPort() {
        service.updateService("app.example.com", null,
            patch(true, null, null, null, null, null, null));

        verify(forPersistingReverseProxyRoutes).setRouteAuthentication("app.example.com", null, true);
        verify(forPersistingReverseProxyRoutes, never()).setRouteDirectUrlDisabled(anyString(), any(), anyBoolean());
        verify(forPersistingReverseProxyRoutes, never()).setRouteRootRedirectPath(anyString(), any(), any());
    }

    @Test
    void updateService_directUrlDisabled_delegatesToPort() {
        service.updateService("app.example.com", null,
            patch(null, false, null, null, null, null, null));

        verify(forPersistingReverseProxyRoutes).setRouteDirectUrlDisabled("app.example.com", null, false);
        verify(forPersistingReverseProxyRoutes, never()).setRouteAuthentication(anyString(), any(), anyBoolean());
    }

    @Test
    void updateService_hiddenFromLaunchpad_delegatesToPort() {
        service.updateService("svc.example.com", "/grafana",
            patch(null, null, true, null, null, null, null));

        verify(forPersistingReverseProxyRoutes).setRouteHiddenFromLaunchpad("svc.example.com", "/grafana", true);
        verify(forPersistingReverseProxyRoutes, never()).setRouteAuthentication(anyString(), any(), anyBoolean());
    }

    @Test
    void updateService_rootRedirectPath_delegatesToPort() {
        service.updateService("app.example.com", null,
            patch(null, null, null, "/dashboard/", null, null, null));

        verify(forPersistingReverseProxyRoutes).setRouteRootRedirectPath("app.example.com", null, "/dashboard/");
    }

    @Test
    void updateService_blankRootRedirectPath_clearsByPassingNull() {
        service.updateService("app.example.com", null,
            patch(null, null, null, "", null, null, null));

        verify(forPersistingReverseProxyRoutes).setRouteRootRedirectPath("app.example.com", null, null);
    }

    @Test
    void updateService_launchpadAlias_trimsAndDelegates() {
        service.updateService("svc.example.com", "/grafana",
            patch(null, null, null, null, "  Grafana Prod ", null, null));

        verify(forPersistingReverseProxyRoutes).setRouteLaunchpadAlias("svc.example.com", "/grafana", "Grafana Prod");
    }

    @Test
    void updateService_blankLaunchpadAlias_clearsByPassingNull() {
        service.updateService("app.example.com", null,
            patch(null, null, null, null, "", null, null));

        verify(forPersistingReverseProxyRoutes).setRouteLaunchpadAlias("app.example.com", null, null);
    }

    @Test
    void updateService_versionEndpointAndProperty_delegatesToPort() {
        service.updateService("app.example.com", null,
            patch(null, null, null, null, null, "/sys/metrics?name[]=system_info", "display"));

        verify(forPersistingReverseProxyRoutes).setRouteVersionEndpoint(
            "app.example.com", null, "/sys/metrics?name[]=system_info", "display");
    }

    @Test
    void updateService_blankVersionEndpoint_clearsBothByPassingNull() {
        service.updateService("app.example.com", null,
            patch(null, null, null, null, null, "   ", ""));

        verify(forPersistingReverseProxyRoutes).setRouteVersionEndpoint(
            "app.example.com", null, null, null);
    }

    @Test
    void updateService_multipleFields_appliesAllSetValues() {
        service.updateService("app.example.com", null,
            patch(true, true, false, "/dashboard/", "Alias", "/status", "build"));

        verify(forPersistingReverseProxyRoutes).setRouteAuthentication("app.example.com", null, true);
        verify(forPersistingReverseProxyRoutes).setRouteDirectUrlDisabled("app.example.com", null, true);
        verify(forPersistingReverseProxyRoutes).setRouteHiddenFromLaunchpad("app.example.com", null, false);
        verify(forPersistingReverseProxyRoutes).setRouteRootRedirectPath("app.example.com", null, "/dashboard/");
        verify(forPersistingReverseProxyRoutes).setRouteLaunchpadAlias("app.example.com", null, "Alias");
        verify(forPersistingReverseProxyRoutes).setRouteVersionEndpoint("app.example.com", null, "/status", "build");
    }

    @Test
    void updateService_authMode_delegatesToSetRouteAuthMode() {
        service.updateService("app.example.com", null,
            new PublishedServicePatch(
                null, null, null, null, null, null, null, "social"));

        verify(forPersistingReverseProxyRoutes).setRouteAuthMode(
            "app.example.com", null, AuthMode.SOCIAL);
        verify(forPersistingReverseProxyRoutes, never()).setRouteAuthentication(anyString(), any(), anyBoolean());
    }

    @Test
    void updateService_authMode_none_disablesAuth() {
        service.updateService("svc.example.com", "/grafana",
            new PublishedServicePatch(
                null, null, null, null, null, null, null, "none"));

        verify(forPersistingReverseProxyRoutes).setRouteAuthMode(
            "svc.example.com", "/grafana", AuthMode.NONE);
    }

    @Test
    void updateService_emptyPatch_writesNothing() {
        service.updateService("app.example.com", null,
            patch(null, null, null, null, null, null, null));

        verifyNoRouteWrites();
    }

    @Test
    void updateService_rejectsVaierService() {
        assertThrows(IllegalArgumentException.class, () ->
            service.updateService("vaier.example.com", null,
                patch(true, null, null, null, null, null, null)));

        verifyNoRouteWrites();
    }

    @Test
    void updateService_invalidatesPublishedServicesCache() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("other.example.com", "10.0.0.2", 9090)
        ));
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.getPublishedServices(); // prime cache
        service.updateService("app.example.com", null,
            patch(true, null, null, null, null, null, null));
        service.getPublishedServices(); // should refetch

        verify(forGettingVpnClients, times(2)).getClients();
    }

    private static PublishedServicePatch patch(
        Boolean requiresAuth, Boolean directUrlDisabled, Boolean hiddenFromLaunchpad,
        String rootRedirectPath, String launchpadAlias, String versionEndpoint, String versionProperty) {
        return new PublishedServicePatch(
            requiresAuth, directUrlDisabled, hiddenFromLaunchpad,
            rootRedirectPath, launchpadAlias, versionEndpoint, versionProperty);
    }

    private void verifyNoRouteWrites() {
        verify(forPersistingReverseProxyRoutes, never()).setRouteAuthentication(anyString(), any(), anyBoolean());
        verify(forPersistingReverseProxyRoutes, never()).setRouteDirectUrlDisabled(anyString(), any(), anyBoolean());
        verify(forPersistingReverseProxyRoutes, never()).setRouteHiddenFromLaunchpad(anyString(), any(), anyBoolean());
        verify(forPersistingReverseProxyRoutes, never()).setRouteRootRedirectPath(anyString(), any(), any());
        verify(forPersistingReverseProxyRoutes, never()).setRouteLaunchpadAlias(anyString(), any(), any());
        verify(forPersistingReverseProxyRoutes, never()).setRouteVersionEndpoint(anyString(), any(), any(), any());
    }

    // --- PublishableService.ignoreKey() ---

    @Test
    void ignoreKey_forPeerService_combinesPeerNameContainerAndPort() {
        PublishableService s = new PublishableService(
            PublishableSource.PEER, TestMachineIds.of("alpha").value(), "alpha", "10.0.0.1",
            "myapp", 8080, null, false);

        assertThat(s.ignoreKey()).isEqualTo("alpha/myapp:8080");
    }

    @Test
    void ignoreKey_forLanServer_combinesAddressContainerAndPort() {
        PublishableService s = new PublishableService(
            PublishableSource.LAN_SERVER, TestMachineIds.of("alpha").value(), "alpha", "192.168.1.50",
            "myapp", 8080, null, false);

        assertThat(s.ignoreKey()).isEqualTo("192.168.1.50/myapp:8080");
    }

    @Test
    void ignoreKey_forVaierServer_combinesContainerAndPort() {
        PublishableService s = new PublishableService(
            PublishableSource.VAIER_SERVER, TestMachineIds.of("Vaier server").value(), null,
            "127.0.0.1", "myapp", 8080, null, false);

        assertThat(s.ignoreKey()).isEqualTo("myapp:8080");
    }

    // --- ignoreService ---

    @Test
    void ignoreService_delegatesToPort() {
        service.ignoreService("my-app");

        verify(forManagingIgnoredServices).ignoreService("my-app");
    }

    // --- unignoreService ---

    @Test
    void unignoreService_delegatesToPort() {
        service.unignoreService("my-app");

        verify(forManagingIgnoredServices).unignoreService("my-app");
    }

    // --- setup helpers ---

    private void setupOneRoute(String domain, String address, int port) {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(route(domain, address, port)));
    }

    private void setupEmptyVpnClients() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
    }

    private void setupEmptyVaierServerServices() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(List.of());
    }

    private ReverseProxyRoute route(String domain, String address, int port) {
        return new ReverseProxyRoute("route", domain, address, port, "svc", null);
    }

    private ReverseProxyRoute routeWithDomain(String domain) {
        return new ReverseProxyRoute("route", domain, "10.0.0.1", 8080, "svc", null);
    }

    // --- getPublishableServices helpers ---

    private PeerContainers unreachablePeer(String name, String ip) {
        return new PeerContainers(TestMachineIds.of(name).value(), name, ip, "UNREACHABLE", List.of(), false, "");
    }

    private PeerContainers okPeer(String name, String ip, List<DockerService> containers) {
        return new PeerContainers(TestMachineIds.of(name).value(), name, ip, "OK", containers, false, "");
    }

    private ForDiscoveringLanServerContainers.LanServerContainers okLanHost(
            String hostName, String hostIp, int port, String relayPeerName,
            List<DockerService> containers) {
        return new ForDiscoveringLanServerContainers.LanServerContainers(
            TestMachineIds.of(hostName).value(), hostName, hostIp, port, relayPeerName, "OK", containers);
    }

    private DockerService peerContainer(String name, int port, String type) {
        return new DockerService("id", name, "image", "latest",
            List.of(new PortMapping(port, port, type, "0.0.0.0")), List.of(), "running");
    }

    /** A container that published a port and is not running — visible to the scrape since #356. */
    private DockerService stoppedContainer(String name, int port) {
        return new DockerService("id", name, "image", "latest",
            List.of(new PortMapping(port, port, "tcp", "0.0.0.0")), List.of(), "exited");
    }

    private DockerService peerContainerWithNullPublicPort(String name, int privatePort, String type) {
        return new DockerService("id", name, "image", "latest",
            List.of(new PortMapping(privatePort, null, type, "0.0.0.0")), List.of(), "running");
    }

    private PublishableService vaierServerService(String name, int port) {
        return new PublishableService(PublishableSource.VAIER_SERVER,
            TestMachineIds.of("Vaier server").value(), null, name, name, port, null, false);
    }

    private ReverseProxyRoute routeForPublishable(String address, int port) {
        return new ReverseProxyRoute("r", "svc.example.com", address, port, "svc", null);
    }
    // --- streams (a non-HTTP port published by SNI on 443) ---
    //
    // A stream rides the same pending/activate/rollback machinery every publish does, so the Explorer's
    // Processing card and the publish-rolled-back event keep working without knowing what kind of route
    // is being written. What differs is what gets written: a tcp: router, and never an auth chain.

    @Test
    void publishingAStream_writesAStreamRouteAndReportsItActive() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("mqtt.example.com")));

        service.activateStream("mqtt", "mqtt.example.com", "172.20.0.1", 1883, false);

        verify(forPersistingReverseProxyRoutes).addStreamRoute("mqtt.example.com", "172.20.0.1", 1883, false);
        verify(forPublishingEvents).publish("published-services", "publish-traefik-active", "mqtt");
    }

    @Test
    void aStreamTraefikNeverPicksUp_rollsBackLikeAnyOtherPublish() {
        service.traefikActivationTimeoutMillis = 50;
        service.traefikActivationRetryIntervalMillis = 10;
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        service.activateStream("mqtt", "mqtt.example.com", "172.20.0.1", 1883, false);

        verify(forPublishingEvents).publish("published-services", "publish-rolled-back", "mqtt");
    }

    @Test
    void publishingAStream_goesThroughThePendingMachinery() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        service.publishService("172.20.0.1", 1883, "mqtt", false, null, false, null, true);

        assertThat(service.getPendingPublications()).extracting(PendingPublication::subdomain)
            .contains("mqtt");
        verify(forPersistingReverseProxyRoutes, timeout(5_000)).addStreamRoute(
            "mqtt.example.com", "172.20.0.1", 1883, false);
        verify(forPersistingReverseProxyRoutes, never()).addReverseProxyRoute(
            anyString(), anyString(), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    void publishingAStreamBehindALogin_isRefused() {
        assertThrows(IllegalArgumentException.class, () ->
            service.publishService("172.20.0.1", 1883, "mqtt", true, null, false, null, true));

        verifyNoInteractions(forPersistingReverseProxyRoutes);
    }

    @Test
    void publishingAStreamUnderAPath_isRefused() {
        assertThrows(IllegalArgumentException.class, () ->
            service.publishService("172.20.0.1", 1883, "mqtt", false, null, false, "/mqtt", true));
    }

    @Test
    void puttingAStreamBehindSocialLogin_isRefused() {
        ReverseProxyRoute stream = streamRoute("mqtt.example.com", "172.20.0.1", 1883);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(stream));

        assertThrows(IllegalArgumentException.class, () -> service.updateService("mqtt.example.com", null,
            new PublishedServicePatch(null, null, null, null, null, null, null, "social")));

        verify(forPersistingReverseProxyRoutes, never()).setRouteAuthMode(any(), any(), any());
    }

    @Test
    void anHttpRoute_canStillBePutBehindSocialLogin() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));

        service.updateService("app.example.com", null,
            new PublishedServicePatch(null, null, null, null, null, null, null, "social"));

        verify(forPersistingReverseProxyRoutes).setRouteAuthMode("app.example.com", null, AuthMode.SOCIAL);
    }

    @Test
    void aPublishedStream_surfacesItselfAsAStreamWithTheAddressToDial() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(streamRoute("mqtt.example.com", "172.20.0.1", 1883)));
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.stream()).isTrue();
        assertThat(result.connectAddress()).isEqualTo("mqtt.example.com:443");
    }

    @Test
    void aPublishedHttpService_hasNoConnectAddress() {
        setupOneRoute("app.example.com", "my-container", 8080);
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.stream()).isFalse();
        assertThat(result.connectAddress()).isNull();
    }

    @Test
    void aStreamOnAMachinesLan_isWrittenAsALanService() {
        // The address decides this, not the operator: an MQTT broker on a NAS is on somebody's LAN, and a
        // route that does not say so is attributed to the Vaier server — wrong machine pane, wrong host
        // state. The HTTP publish has always dispatched on the address; a stream must too.
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.5", "",
                MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5")
        ));
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        service.publishService("192.168.3.50", 1883, "mqtt", false, null, false, null, true);

        verify(forPersistingReverseProxyRoutes, timeout(5_000))
            .addStreamRoute("mqtt.example.com", "192.168.3.50", 1883, true);
    }

    @Test
    void aStreamOnTheVaierServersOwnBridge_isNoLanService() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        service.publishService("172.20.0.1", 1883, "mqtt", false, null, false, null, true);

        verify(forPersistingReverseProxyRoutes, timeout(5_000))
            .addStreamRoute("mqtt.example.com", "172.20.0.1", 1883, false);
    }

    @Test
    void aLanStream_readsBackUnderTheMachineItRunsOn() {
        // The whole point of the marker: hostMachineId resolves a LAN route against the registered LAN
        // servers. Without it the route falls back to the Vaier server's identity and lands on its pane.
        LanServer nasBox = new LanServer("nas-box", "192.168.3.50", false, null);
        when(forPersistingLanServers.getAll()).thenReturn(List.of(nasBox));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.5", "",
                MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5")
        ));
        ReverseProxyRoute lanStream = ReverseProxyRoute.builder()
            .name("mqtt-example-com-router").domainName("mqtt.example.com").address("192.168.3.50")
            .port(1883).service("mqtt-example-com-service").stream(true)
            .isLanService(true).protocol(ReverseProxyRoute.STREAM_PROTOCOL).build();
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(lanStream));
        setupEmptyVpnClients();
        setupEmptyVaierServerServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.isLanService()).isTrue();
        assertThat(result.machineId()).isEqualTo(nasBox.machineId().value());
        assertThat(result.stream()).isTrue();
    }

    @Test
    void editingAnythingOnAStream_isRefusedBeforeAnythingIsWritten() {
        // Not just the sign-in picker: a redirect, an alias, a version probe, the legacy requiresAuth
        // toggle. The adapter cannot find a stream's router in the http: section, so an unguarded edit
        // came back as a 500 or wrote a sidecar nothing reads.
        ReverseProxyRoute stream = streamRoute("mqtt.example.com", "172.20.0.1", 1883);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(stream));

        List<PublishedServicePatch> refused = List.of(
            new PublishedServicePatch(true, null, null, null, null, null, null),
            new PublishedServicePatch(null, true, null, null, null, null, null),
            new PublishedServicePatch(null, null, true, null, null, null, null),
            new PublishedServicePatch(null, null, null, "/dashboard", null, null, null),
            new PublishedServicePatch(null, null, null, null, "Broker", null, null),
            new PublishedServicePatch(null, null, null, null, null, "/status", "build"));

        refused.forEach(patch -> assertThrows(IllegalArgumentException.class,
            () -> service.updateService("mqtt.example.com", null, patch)));

        verify(forPersistingReverseProxyRoutes, never()).setRouteAuthMode(any(), any(), any());
        verify(forPersistingReverseProxyRoutes, never()).setRouteRootRedirectPath(any(), any(), any());
        verify(forPersistingReverseProxyRoutes, never()).setRouteDirectUrlDisabled(any(), any(), anyBoolean());
        verify(forPersistingReverseProxyRoutes, never()).setRouteHiddenFromLaunchpad(any(), any(), anyBoolean());
        verify(forPersistingReverseProxyRoutes, never()).setRouteLaunchpadAlias(any(), any(), any());
        verify(forPersistingReverseProxyRoutes, never()).setRouteVersionEndpoint(any(), any(), any(), any());
    }

    @Test
    void editingAnHttpService_isUntouchedByTheStreamGuard() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));

        service.updateService("app.example.com", null,
            new PublishedServicePatch(null, null, null, "/dashboard", null, null, null));

        verify(forPersistingReverseProxyRoutes).setRouteRootRedirectPath("app.example.com", null, "/dashboard");
    }

    @Test
    void aSubdomainThatCouldWidenATraefikRule_neverReachesTheRoutes() {
        // Both doors: the HTTP publish and the stream publish take the same operator-typed label.
        assertThrows(IllegalArgumentException.class, () ->
            service.publishService("10.13.13.6", 8080, "app`) || HostSNI(`", false, null, false, null, false));
        assertThrows(IllegalArgumentException.class, () ->
            service.publishService("172.20.0.1", 1883, "mqtt`", false, null, false, null, true));

        verifyNoInteractions(forPersistingReverseProxyRoutes);
    }

    @Test
    void aHandPublishedLanService_isHeldToTheSameLabelRule() {
        LanServer nasBox = new LanServer("nas-box", "192.168.3.50", false, null);
        when(forPersistingLanServers.getAll()).thenReturn(List.of(nasBox));

        assertThrows(IllegalArgumentException.class, () -> service.publishLanService(
            "nas`) || Host(`", nasBox.machineId(), 5000, "https", false, false, null, null));

        verifyNoInteractions(forPersistingReverseProxyRoutes);
    }

    private ReverseProxyRoute streamRoute(String domain, String address, int port) {
        return ReverseProxyRoute.builder().name(ReverseProxyRoute.routerName(domain, null))
            .domainName(domain).address(address).port(port)
            .service(ReverseProxyRoute.serviceName(domain, null)).stream(true).build();
    }
}
