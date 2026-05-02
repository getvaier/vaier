package net.vaier.application.service;

import net.vaier.application.DiscoverLanServerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase.PeerContainers;
import net.vaier.application.GetLocalDockerServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase.PublishedServiceUco;
import net.vaier.application.PublishPeerServiceUseCase.PendingPublication;
import net.vaier.application.PublishPeerServiceUseCase.PublishStatus;
import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.application.PublishPeerServiceUseCase.PublishableSource;
import net.vaier.config.ConfigResolver;
import net.vaier.config.ServiceNames;
import net.vaier.domain.*;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.Server.State;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForManagingIgnoredServices;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForResolvingDns;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublishingServiceTest {

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
    ConfigResolver configResolver;

    @Mock
    ForPublishingEvents forPublishingEvents;

    @Mock
    ForResolvingDns forResolvingDns;

    @Mock
    ForManagingIgnoredServices forManagingIgnoredServices;

    @Mock
    PendingPublicationsService pendingPublicationsService;

    @Mock
    DiscoverPeerContainersUseCase discoverPeerContainersUseCase;

    @Mock
    DiscoverLanServerContainersUseCase discoverLanServerContainersUseCase;

    @Mock
    GetLocalDockerServicesUseCase getLocalDockerServicesUseCase;

    @InjectMocks
    PublishingService service;

    @BeforeEach
    void setUp() {
        lenient().when(configResolver.getDomain()).thenReturn("example.com");
    }

    @Test
    void getPublishedServices_emptyRoutes_returnsEmptyWithoutCallingOtherPorts() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        assertThat(service.getPublishedServices()).isEmpty();
        verifyNoInteractions(forPersistingDnsRecords, forGettingVpnClients, forGettingServerInfo);
    }

    @Test
    void getPublishedServices_routeWithCnameRecord_dnsStateOk() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.dnsState()).isEqualTo(DnsState.OK);
    }

    @Test
    void getPublishedServices_routeWithARecord_dnsStateOk() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupDnsRecord("app.example.com", DnsRecordType.A);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.dnsState()).isEqualTo(DnsState.OK);
    }

    @Test
    void getPublishedServices_routeWithNoMatchingDnsRecord_dnsStateNonExisting() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupDnsRecord("other.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.dnsState()).isEqualTo(DnsState.NON_EXISTING);
    }

    @Test
    void getPublishedServices_routeWithNoDnsRecordsAtAll_dnsStateNonExisting() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.dnsState()).isEqualTo(DnsState.NON_EXISTING);
    }

    @Test
    void getPublishedServices_runningLocalService_hostStateOk() {
        setupOneRoute("app.example.com", "my-container", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(
            List.of(new DockerService("id", "my-container", "image", "latest",
                List.of(new DockerService.PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of(), "running"))
        );

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.OK);
    }

    @Test
    void getPublishedServices_stoppedLocalService_hostStateUnreachable() {
        setupOneRoute("app.example.com", "my-container", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(
            List.of(new DockerService("id", "my-container", "image", "latest",
                List.of(new DockerService.PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of(), "exited"))
        );

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void getPublishedServices_vpnPeerWithRecentHandshake_hostStateOk() {
        String recentHandshake = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        setupOneRoute("app.example.com", "10.13.13.2", 8080);
        setupNoDnsRecords();
        when(forGettingVpnClients.getClients()).thenReturn(
            List.of(new VpnClient("pubkey", "10.13.13.2/32", "1.2.3.4", "51820", recentHandshake, "0", "0"))
        );
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        setupEmptyLocalServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.OK);
    }

    @Test
    void getPublishedServices_vpnPeerWithStaleHandshake_hostStateUnreachable() {
        setupOneRoute("app.example.com", "10.13.13.2", 8080);
        setupNoDnsRecords();
        when(forGettingVpnClients.getClients()).thenReturn(
            List.of(new VpnClient("pubkey", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0"))
        );
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        setupEmptyLocalServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void getPublishedServices_noLocalServiceAndNoVpnClientMatch_hostStateUnreachable() {
        setupOneRoute("app.example.com", "192.168.99.1", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

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
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        assertThat(service.getPublishedServices().get(0).authenticated()).isTrue();
    }

    @Test
    void getPublishedServices_routeWithNoAuth_authenticatedIsFalse() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        assertThat(service.getPublishedServices().get(0).authenticated()).isFalse();
    }

    @Test
    void getPublishedServices_vaierInfrastructureRouter_isExcluded() {
        setupOneRoute(ServiceNames.VAIER + ".example.com", "10.0.0.1", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        assertThat(service.getPublishedServices()).isEmpty();
    }

    @Test
    void getPublishedServices_autheliaInfrastructureRouter_isExcluded() {
        setupOneRoute("login.example.com", "10.0.0.1", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        assertThat(service.getPublishedServices()).isEmpty();
    }

    @Test
    void getPublishedServices_mixOfInfraAndUserRoutes_returnsOnlyUserRoutes() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route(ServiceNames.VAIER + ".example.com", "vaier", 8080),
            route("login.example.com", "authelia", 9091),
            route("app.example.com", "10.0.0.1", 8080)
        ));
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        assertThat(service.getPublishedServices()).hasSize(1);
        assertThat(service.getPublishedServices().get(0).dnsAddress()).isEqualTo("app.example.com");
    }

    @Test
    void getPublishedServices_expensivePortsCalledExactlyOnce() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("app.example.com", "10.0.0.1", 8080),
            route("db.example.com", "10.0.0.2", 5432)
        ));
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.getPublishedServices();

        verify(forPersistingDnsRecords, times(1)).getDnsZones();
        verify(forGettingVpnClients, times(1)).getClients();
        verify(forGettingServerInfo, times(1)).getServicesWithExposedPorts(any());
    }

    @Test
    void getPublishedServices_secondCall_returnsCachedResultWithoutRefetching() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("app.example.com", "10.0.0.1", 8080)
        ));
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.getPublishedServices();
        service.getPublishedServices();

        verify(forPersistingReverseProxyRoutes, times(1)).getReverseProxyRoutes();
        verify(forPersistingDnsRecords, times(1)).getDnsZones();
        verify(forGettingVpnClients, times(1)).getClients();
        verify(forGettingServerInfo, times(1)).getServicesWithExposedPorts(any());
    }

    @Test
    void getPublishedServices_afterInvalidation_refetchesFromPorts() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("app.example.com", "10.0.0.1", 8080)
        ));
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.getPublishedServices();
        service.invalidatePublishedServicesCache();
        service.getPublishedServices();

        verify(forPersistingDnsRecords, times(2)).getDnsZones();
        verify(forGettingVpnClients, times(2)).getClients();
        verify(forGettingServerInfo, times(2)).getServicesWithExposedPorts(any());
    }

    @Test
    void getPublishedServices_localService_nameIsSubdomainAtLocal() {
        setupOneRoute("pihole.example.com", "pihole", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(
            List.of(new DockerService("id", "pihole", "image", "latest",
                List.of(new DockerService.PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of(), "running"))
        );

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.name()).isEqualTo("pihole @ local");
    }

    @Test
    void getPublishedServices_peerService_nameIsSubdomainAtPeerName() {
        String recentHandshake = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        setupOneRoute("pihole.myserver.example.com", "10.13.13.2", 8080);
        setupNoDnsRecords();
        when(forGettingVpnClients.getClients()).thenReturn(
            List.of(new VpnClient("pubkey", "10.13.13.2/32", "1.2.3.4", "51820", recentHandshake, "0", "0"))
        );
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("myserver");
        setupEmptyLocalServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.name()).isEqualTo("pihole @ myserver");
    }

    @Test
    void getPublishedServices_unknownIpAddress_nameShowsLocal() {
        setupOneRoute("app.example.com", "10.13.13.5", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.name()).isEqualTo("app @ local");
    }

    @Test
    void getPublishedServices_simpleSubdomain_extractedCorrectly() {
        setupOneRoute("traefik.example.com", "traefik", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.name()).isEqualTo("traefik @ local");
    }

    @Test
    void getPublishedServices_peerServiceOnSamePortAsLocal_resolvesToPeerNotLocal() {
        String recentHandshake = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        setupOneRoute("openhab.myserver.example.com", "10.13.13.3", 8080);
        setupNoDnsRecords();
        when(forGettingVpnClients.getClients()).thenReturn(
            List.of(new VpnClient("pubkey", "10.13.13.3/32", "1.2.3.4", "51820", recentHandshake, "0", "0"))
        );
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.3")).thenReturn("myserver");
        // Local traefik also listens on port 8080
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(
            List.of(new DockerService("id", "traefik", "image", "latest",
                List.of(new DockerService.PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of(), "running"))
        );

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.name()).isEqualTo("openhab @ myserver");
    }

    // --- publishService / getPublishStatus / getPendingPublications ---

    @Test
    void publishService_createsCnameDnsRecord() {
        service.publishService("10.0.0.1", 8080, "app", false, null, false);

        ArgumentCaptor<DnsRecord> recordCaptor = ArgumentCaptor.forClass(DnsRecord.class);
        verify(forPersistingDnsRecords).addDnsRecord(recordCaptor.capture(), any());

        DnsRecord record = recordCaptor.getValue();
        assertThat(record.name()).isEqualTo("app.example.com.");
        assertThat(record.type()).isEqualTo(DnsRecordType.CNAME);
        assertThat(record.values()).containsExactly("vaier.example.com.");
    }

    @Test
    void getPublishStatus_routeExistsInTraefik_returnsTrueTrue() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            routeWithDomain("app.example.com")
        ));

        PublishStatus status = service.getPublishStatus("app");

        assertThat(status.dnsPropagated()).isTrue();
        assertThat(status.traefikActive()).isTrue();
    }

    @Test
    void getPublishStatus_notInPendingNotInRoutes_returnsFalseFalse() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        PublishStatus status = service.getPublishStatus("app");

        assertThat(status.dnsPropagated()).isFalse();
        assertThat(status.traefikActive()).isFalse();
    }

    @Test
    void getPublishStatus_afterPublish_inPendingReturnsPendingStatus() {
        // publishService adds to pending map; DNS polling runs async but we're testing the sync state
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        service.publishService("10.0.0.1", 8080, "app", false, null, false);

        // Immediately after publish, route not yet in Traefik -> not in routes, but in pending with (false, false)
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        PublishStatus status = service.getPublishStatus("app");

        assertThat(status.dnsPropagated()).isFalse();
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
    void publishService_emitsDnsCreatedEvent() {
        service.publishService("10.0.0.1", 8080, "app", false, null, false);

        verify(forPublishingEvents).publish("published-services", "publish-dns-created", "app");
    }

    @Test
    void getPendingPublications_afterPublish_returnsEntry() {
        service.publishService("10.0.0.1", 8080, "app", true, null, false);
        List<PendingPublication> pending = service.getPendingPublications();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).subdomain()).isEqualTo("app");
        assertThat(pending.get(0).requiresAuth()).isTrue();
        assertThat(pending.get(0).dnsPropagated()).isFalse();
    }

    // --- waitForDnsThenActivate ---

    @Test
    void waitForDnsThenActivate_waitsForTraefikRouteBeforeFiringEvent() {
        when(forResolvingDns.isResolvable("app.example.com")).thenReturn(true);
        // First getReverseProxyRoutes call: Traefik not yet loaded; second call: route present
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of())
            .thenReturn(List.of(routeWithDomain("app.example.com")));

        service.waitForDnsThenActivate("app", "app.example.com", "10.0.0.1", 8080, false, null, false);

        InOrder inOrder = inOrder(forPersistingReverseProxyRoutes, forPublishingEvents);
        inOrder.verify(forPersistingReverseProxyRoutes).addReverseProxyRoute("app.example.com", "10.0.0.1", 8080, false, null);
        inOrder.verify(forPublishingEvents).publish("published-services", "publish-traefik-active", "app");
        verify(forPersistingReverseProxyRoutes, atLeast(2)).getReverseProxyRoutes();
    }

    @Test
    void waitForDnsThenActivate_invalidatesPublishedServicesCacheAfterActivation() {
        // PublishingService is now its own cache invalidator. Verify observable behaviour:
        // getPublishedServices called before and after waitForDnsThenActivate re-fetches from ports.
        when(forResolvingDns.isResolvable("app.example.com")).thenReturn(true);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        // Prime cache
        service.getPublishedServices();

        service.waitForDnsThenActivate("app", "app.example.com", "10.0.0.1", 8080, false, null, false);

        // Cache should have been invalidated -> next call re-fetches
        service.getPublishedServices();

        verify(forPersistingDnsRecords, times(2)).getDnsZones();
    }

    @Test
    void waitForDnsThenActivate_directUrlDisabledTrue_persistsFlagAfterRouteCreated() {
        when(forResolvingDns.isResolvable("app.example.com")).thenReturn(true);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));

        service.waitForDnsThenActivate("app", "app.example.com", "10.0.0.1", 8080, false, null, true);

        InOrder inOrder = inOrder(forPersistingReverseProxyRoutes);
        inOrder.verify(forPersistingReverseProxyRoutes).addReverseProxyRoute("app.example.com", "10.0.0.1", 8080, false, null);
        inOrder.verify(forPersistingReverseProxyRoutes).setRouteDirectUrlDisabled("app.example.com", true);
    }

    @Test
    void waitForDnsThenActivate_directUrlDisabledFalse_doesNotPersistFlag() {
        when(forResolvingDns.isResolvable("app.example.com")).thenReturn(true);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));

        service.waitForDnsThenActivate("app", "app.example.com", "10.0.0.1", 8080, false, null, false);

        verify(forPersistingReverseProxyRoutes, never()).setRouteDirectUrlDisabled(anyString(), anyBoolean());
    }

    @Test
    void waitForDnsThenActivate_dnsNeverResolves_doesNotWriteTraefikRoute() {
        when(forResolvingDns.isResolvable("app.example.com")).thenReturn(false);
        ReflectionTestUtils.setField(service, "dnsTimeoutMillis", 100L);
        ReflectionTestUtils.setField(service, "dnsRetryIntervalMillis", 10L);

        service.waitForDnsThenActivate("app", "app.example.com", "10.0.0.1", 8080, false, null, false);

        verify(forPersistingReverseProxyRoutes, never()).addReverseProxyRoute(anyString(), anyString(), anyInt(), anyBoolean(), any());
    }

    @Test
    void waitForDnsThenActivate_dnsNeverResolves_emitsTimeoutEvent() {
        when(forResolvingDns.isResolvable("app.example.com")).thenReturn(false);
        ReflectionTestUtils.setField(service, "dnsTimeoutMillis", 50L);
        ReflectionTestUtils.setField(service, "dnsRetryIntervalMillis", 10L);

        service.waitForDnsThenActivate("app", "app.example.com", "10.0.0.1", 8080, false, null, false);

        verify(forPublishingEvents).publish("published-services", "publish-dns-timeout", "app");
    }

    @Test
    void waitForDnsThenActivate_dnsNeverResolves_deletesCnameAndEmitsRollback() {
        when(forResolvingDns.isResolvable("app.example.com")).thenReturn(false);
        ReflectionTestUtils.setField(service, "dnsTimeoutMillis", 50L);
        ReflectionTestUtils.setField(service, "dnsRetryIntervalMillis", 10L);

        service.waitForDnsThenActivate("app", "app.example.com", "10.0.0.1", 8080, false, null, false);

        verify(forPersistingDnsRecords).deleteDnsRecord(
            eq("app.example.com."),
            eq(DnsRecordType.CNAME),
            any(DnsZone.class));
        verify(forPublishingEvents).publish("published-services", "publish-rolled-back", "app");
    }

    @Test
    void waitForDnsThenActivate_traefikAddThrows_deletesCnameAndEmitsRollback() {
        when(forResolvingDns.isResolvable("app.example.com")).thenReturn(true);
        doThrow(new RuntimeException("traefik boom")).when(forPersistingReverseProxyRoutes)
            .addReverseProxyRoute(anyString(), anyString(), anyInt(), anyBoolean(), any());

        service.waitForDnsThenActivate("app", "app.example.com", "10.0.0.1", 8080, false, null, false);

        verify(forPersistingDnsRecords).deleteDnsRecord(
            eq("app.example.com."),
            eq(DnsRecordType.CNAME),
            any(DnsZone.class));
        verify(forPublishingEvents).publish("published-services", "publish-rolled-back", "app");
    }

    @Test
    void waitForDnsThenActivate_traefikNeverPicksUpRoute_deletesRouteAndCnameAndEmitsRollback() {
        when(forResolvingDns.isResolvable("app.example.com")).thenReturn(true);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "traefikActivationTimeoutMillis", 50L);
        ReflectionTestUtils.setField(service, "traefikActivationRetryIntervalMillis", 10L);

        service.waitForDnsThenActivate("app", "app.example.com", "10.0.0.1", 8080, false, null, false);

        verify(forPersistingReverseProxyRoutes).deleteReverseProxyRouteByDnsName("app.example.com");
        verify(forPersistingDnsRecords).deleteDnsRecord(
            eq("app.example.com."),
            eq(DnsRecordType.CNAME),
            any(DnsZone.class));
        verify(forPublishingEvents).publish("published-services", "publish-rolled-back", "app");
    }

    @Test
    void waitForDnsThenActivate_happyPath_doesNotEmitRollback() {
        when(forResolvingDns.isResolvable("app.example.com")).thenReturn(true);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));

        service.waitForDnsThenActivate("app", "app.example.com", "10.0.0.1", 8080, false, null, false);

        verify(forPublishingEvents, never()).publish(anyString(), eq("publish-rolled-back"), anyString());
        verify(forPersistingDnsRecords, never()).deleteDnsRecord(anyString(), any(), any());
    }

    @Test
    void waitForDnsThenActivate_addressMatchesLocalContainerIp_normalizesToContainerName() {
        when(forResolvingDns.isResolvable("app.example.com")).thenReturn(true);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));
        when(forGettingServerInfo.findContainerNameByIp(any(Server.class), eq("172.20.0.3")))
            .thenReturn(Optional.of("vaier"));

        service.waitForDnsThenActivate("app", "app.example.com", "172.20.0.3", 8080, false, null, false);

        verify(forPersistingReverseProxyRoutes).addReverseProxyRoute(
            "app.example.com", "vaier", 8080, false, null);
    }

    @Test
    void waitForDnsThenActivate_addressIsNotALocalContainerIp_passesAddressThroughUnchanged() {
        when(forResolvingDns.isResolvable("app.example.com")).thenReturn(true);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));
        when(forGettingServerInfo.findContainerNameByIp(any(Server.class), eq("10.13.13.3")))
            .thenReturn(Optional.empty());

        service.waitForDnsThenActivate("app", "app.example.com", "10.13.13.3", 8080, false, null, false);

        verify(forPersistingReverseProxyRoutes).addReverseProxyRoute(
            "app.example.com", "10.13.13.3", 8080, false, null);
    }

    @Test
    void publishService_addressInsideRelayLanCidr_dispatchesToLanFlow() {
        // Discovered LAN docker services hit the regular /publish endpoint with a LAN IP. They must be
        // recognised and routed through the LAN flow instead — otherwise the resulting route gets
        // isLanService=false and the dashboard's directUrl() can't find the relay peer (#180).
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration(
                "apalveien5", "10.13.13.5", "", MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5")
        ));

        service.publishService("192.168.3.50", 80, "pihole", false, null, false);

        // The peer flow tracks pendingPublications by (address, port); the LAN flow does not, so
        // skipping it is the observable signal that the LAN dispatch happened.
        verify(pendingPublicationsService, never()).track(anyString(), anyInt());
        verify(forPublishingEvents).publish("published-services", "publish-dns-created", "pihole");
    }

    @Test
    void publishService_addressOutsideAllRelayLanCidrs_usesPeerFlow() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration(
                "apalveien5", "10.13.13.5", "", MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5")
        ));

        service.publishService("10.13.13.5", 8080, "app", false, null, false);

        verify(pendingPublicationsService).track("10.13.13.5", 8080);
    }

    // --- publishLanService (#175) ---

    @Test
    void publishLanService_targetIpOutsideEveryRelayLanCidr_throws() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration(
                "apalveien5", "10.13.13.5", "", MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5")
        ));

        assertThrows(IllegalArgumentException.class, () ->
            service.publishLanService("nas", "10.99.99.99", 5000, "http", false, false)
        );
        verify(forPersistingDnsRecords, never()).addDnsRecord(any(), any());
        verify(forPersistingReverseProxyRoutes, never()).addLanReverseProxyRoute(
            anyString(), anyString(), anyInt(), anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void publishLanService_targetIpInsideRelayLanCidr_createsCnameDnsRecord() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration(
                "apalveien5", "10.13.13.5", "", MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5")
        ));

        service.publishLanService("nas", "192.168.3.50", 5000, "https", false, false);

        ArgumentCaptor<DnsRecord> recordCaptor = ArgumentCaptor.forClass(DnsRecord.class);
        verify(forPersistingDnsRecords).addDnsRecord(recordCaptor.capture(), any());
        DnsRecord record = recordCaptor.getValue();
        assertThat(record.name()).isEqualTo("nas.example.com.");
        assertThat(record.type()).isEqualTo(DnsRecordType.CNAME);
        assertThat(record.values()).containsExactly("vaier.example.com.");
    }

    @Test
    void publishLanService_targetIpInsideRelayLanCidr_writesLanTraefikRouteAfterDnsPropagates() {
        when(forResolvingDns.isResolvable("nas.example.com")).thenReturn(true);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("nas.example.com")));

        service.waitForLanDnsThenActivate(
            "nas", "nas.example.com", "192.168.3.50", 5000, "https", true, true);

        verify(forPersistingReverseProxyRoutes).addLanReverseProxyRoute(
            "nas.example.com", "192.168.3.50", 5000, "https", true, true);
    }

    // --- deleteService ---

    @Test
    void deleteService_deletesTraefikRouteByFqdn() {
        service.deleteService("app.example.com");

        verify(forPersistingReverseProxyRoutes).deleteReverseProxyRouteByDnsName("app.example.com");
    }

    @Test
    void deleteService_deletesDnsCnameRecordWithCorrectZone() {
        service.deleteService("app.example.com");

        verify(forPersistingDnsRecords).deleteDnsRecord("app.example.com", DnsRecordType.CNAME, new DnsZone("example.com"));
    }

    @Test
    void deleteService_deletesTraefikBeforeDns() {
        service.deleteService("app.example.com");

        InOrder order = inOrder(forPersistingReverseProxyRoutes, forPersistingDnsRecords);
        order.verify(forPersistingReverseProxyRoutes).deleteReverseProxyRouteByDnsName("app.example.com");
        order.verify(forPersistingDnsRecords).deleteDnsRecord("app.example.com", DnsRecordType.CNAME, new DnsZone("example.com"));
    }

    @Test
    void deleteService_rejectsVaierService() {
        assertThrows(IllegalArgumentException.class, () -> service.deleteService("vaier.example.com"));

        verifyNoInteractions(forPersistingReverseProxyRoutes, forPersistingDnsRecords);
    }

    @Test
    void deleteService_rejectsAuthService() {
        assertThrows(IllegalArgumentException.class, () -> service.deleteService("login.example.com"));

        verifyNoInteractions(forPersistingReverseProxyRoutes, forPersistingDnsRecords);
    }

    @Test
    void deleteService_waitsForTraefikRouteToDisappearBeforeReturning() {
        // route present -> empty (transitional) -> empty (stable): needs 2 consecutive absences
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(new ReverseProxyRoute("r", "app.example.com", "10.0.0.1", 8080, "svc", null)))
            .thenReturn(List.of());

        service.deleteService("app.example.com");

        InOrder order = inOrder(forPersistingReverseProxyRoutes, forPersistingDnsRecords);
        order.verify(forPersistingReverseProxyRoutes).deleteReverseProxyRouteByDnsName("app.example.com");
        order.verify(forPersistingReverseProxyRoutes, atLeast(3)).getReverseProxyRoutes();
        order.verify(forPersistingDnsRecords).deleteDnsRecord(any(), any(), any());
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
    void deleteService_emptyDomain_passesDnsZoneWithEmptyString() {
        when(configResolver.getDomain()).thenReturn("");

        service.deleteService("app.example.com");

        verify(forPersistingDnsRecords).deleteDnsRecord("app.example.com", DnsRecordType.CNAME, new DnsZone(""));
    }

    @Test
    void deleteService_invalidatesPublishedServicesCache() {
        // PublishingService is its own cache invalidator. Verify observable behaviour:
        // cache re-fetches from ports after deleteService completes.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("other.example.com", "10.0.0.2", 9090)
        ));
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.getPublishedServices(); // prime cache
        service.deleteService("app.example.com");
        service.getPublishedServices(); // should refetch

        verify(forPersistingDnsRecords, times(2)).getDnsZones();
    }

    // --- getPublishableServices ---

    @Test
    void getPublishableServices_allPeersUnreachable_returnsOnlyLocalServices() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            unreachablePeer("alice", "10.13.13.2")
        ));
        PublishableService localSvc = localService("my-app", 3000);
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of(localSvc));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).containsExactly(localSvc);
    }

    @Test
    void getPublishableServices_peerWithTcpPortNotPublished_included() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainer("my-app", 8080, "tcp")))
        ));
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).address()).isEqualTo("10.13.13.2");
        assertThat(result.get(0).port()).isEqualTo(8080);
        assertThat(result.get(0).source()).isEqualTo(PublishableSource.PEER);
    }

    @Test
    void getPublishableServices_peerPortAlreadyPublished_excluded() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            routeForPublishable("10.13.13.2", 8080)
        ));
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainer("my-app", 8080, "tcp")))
        ));
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_peerWithExposedPortRange_excluded() {
        // #189 — a container that exposes a contiguous range (e.g. RoonServer 9100-9339)
        // surfaces as a single range PortMapping; it must NOT explode the publishable list.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        DockerService roon = new DockerService("id", "roonserver", "image", "latest",
            List.of(new PortMapping(9100, 9339, 9100, "tcp", "0.0.0.0")), List.of(), "running");
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            okPeer("media", "10.13.13.5", List.of(roon))
        ));
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_peerWithUdpPort_excluded() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainer("dns-server", 53, "udp")))
        ));
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_mergesPeerAndLocalServices() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainer("peer-app", 9000, "tcp")))
        ));
        PublishableService localSvc = localService("local-app", 3000);
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of(localSvc));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PublishableService::source)
            .containsExactlyInAnyOrder(PublishableSource.PEER, PublishableSource.LOCAL);
    }

    @Test
    void getPublishableServices_duplicates_deduplicated() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of());
        PublishableService svc = localService("my-app", 3000);
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any()))
            .thenReturn(List.of(svc, svc));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
    }

    @Test
    void getPublishableServices_noPeersNoLocal_returnsEmpty() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of());
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_pendingPublication_excluded() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainer("my-app", 8080, "tcp")))
        ));
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());
        when(pendingPublicationsService.isPending("10.13.13.2", 8080)).thenReturn(true);
        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_peerPortWithNullPublicPort_excluded() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainerWithNullPublicPort("my-app", 8080, "tcp")))
        ));
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_ignoredLocalService_markedIgnored() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of());
        PublishableService svc = localService("boring-app", 3000);
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of(svc));
        when(forManagingIgnoredServices.getIgnoredServiceKeys()).thenReturn(Set.of("boring-app:3000"));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ignored()).isTrue();
    }

    @Test
    void getPublishableServices_ignoredPeerService_markedIgnored() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(peerContainer("peer-app", 8080, "tcp")))
        ));
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());
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
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of());
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());
        when(discoverLanServerContainersUseCase.discoverAllLanServerContainers()).thenReturn(List.of(
            okLanHost("nas", "192.168.1.50", 2375, "apalveien",
                List.of(peerContainer("pihole", 80, "tcp")))
        ));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
        PublishableService svc = result.getFirst();
        assertThat(svc.source()).isEqualTo(PublishableSource.LAN_SERVER);
        assertThat(svc.peerName()).isEqualTo("apalveien");
        assertThat(svc.address()).isEqualTo("192.168.1.50");
        assertThat(svc.containerName()).isEqualTo("pihole");
        assertThat(svc.port()).isEqualTo(80);
    }

    @Test
    void getPublishableServices_ignoredLanDockerHostService_markedIgnored() {
        // Ignore key for LAN_SERVER uses the LAN host IP (not the relay peer name) so two
        // LAN hosts behind the same relay with same-named containers don't collide.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of());
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());
        when(discoverLanServerContainersUseCase.discoverAllLanServerContainers()).thenReturn(List.of(
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
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of());
        PublishableService svc = localService("useful-app", 3000);
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of(svc));
        when(forManagingIgnoredServices.getIgnoredServiceKeys()).thenReturn(Set.of());

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ignored()).isFalse();
    }

    // --- setAuthentication ---

    @Test
    void setAuthentication_true_delegatesToPort() {
        service.setAuthentication("app.example.com", true);

        verify(forPersistingReverseProxyRoutes).setRouteAuthentication("app.example.com", true);
    }

    @Test
    void setAuthentication_false_delegatesToPort() {
        service.setAuthentication("app.example.com", false);

        verify(forPersistingReverseProxyRoutes).setRouteAuthentication("app.example.com", false);
    }

    @Test
    void setAuthentication_rejectsVaierService() {
        assertThrows(IllegalArgumentException.class, () -> service.setAuthentication("vaier.example.com", true));

        verify(forPersistingReverseProxyRoutes, never()).setRouteAuthentication(anyString(), anyBoolean());
    }

    @Test
    void setAuthentication_rejectsAuthService() {
        assertThrows(IllegalArgumentException.class, () -> service.setAuthentication("login.example.com", false));

        verify(forPersistingReverseProxyRoutes, never()).setRouteAuthentication(anyString(), anyBoolean());
    }

    @Test
    void setAuthentication_invalidatesPublishedServicesCache() {
        // PublishingService is its own cache invalidator — verify observable behaviour.
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("other.example.com", "10.0.0.2", 9090)
        ));
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.getPublishedServices(); // prime cache
        service.setAuthentication("app.example.com", true);
        service.getPublishedServices(); // should refetch

        verify(forPersistingDnsRecords, times(2)).getDnsZones();
    }

    // --- setDirectUrlDisabled ---

    @Test
    void setDirectUrlDisabled_true_delegatesToPort() {
        service.setDirectUrlDisabled("app.example.com", true);

        verify(forPersistingReverseProxyRoutes).setRouteDirectUrlDisabled("app.example.com", true);
    }

    @Test
    void setDirectUrlDisabled_false_delegatesToPort() {
        service.setDirectUrlDisabled("app.example.com", false);

        verify(forPersistingReverseProxyRoutes).setRouteDirectUrlDisabled("app.example.com", false);
    }

    @Test
    void setDirectUrlDisabled_rejectsVaierService() {
        assertThrows(IllegalArgumentException.class, () -> service.setDirectUrlDisabled("vaier.example.com", true));

        verify(forPersistingReverseProxyRoutes, never()).setRouteDirectUrlDisabled(anyString(), anyBoolean());
    }

    @Test
    void setDirectUrlDisabled_invalidatesPublishedServicesCache() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("other.example.com", "10.0.0.2", 9090)
        ));
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.getPublishedServices(); // prime cache
        service.setDirectUrlDisabled("app.example.com", true);
        service.getPublishedServices(); // should refetch

        verify(forPersistingDnsRecords, times(2)).getDnsZones();
    }

    // --- setRootRedirectPath ---

    @Test
    void setRootRedirectPath_delegatesToPort() {
        service.setRootRedirectPath("app.example.com", "/dashboard/");

        verify(forPersistingReverseProxyRoutes).setRouteRootRedirectPath("app.example.com", "/dashboard/");
    }

    @Test
    void setRootRedirectPath_nullPath_removesRedirect() {
        service.setRootRedirectPath("app.example.com", null);

        verify(forPersistingReverseProxyRoutes).setRouteRootRedirectPath("app.example.com", null);
    }

    @Test
    void setRootRedirectPath_invalidatesCache() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("other.example.com", "10.0.0.2", 9090)
        ));
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.getPublishedServices(); // prime cache
        service.setRootRedirectPath("app.example.com", "/dashboard/");
        service.getPublishedServices(); // should refetch

        verify(forPersistingDnsRecords, times(2)).getDnsZones();
    }

    @Test
    void setRootRedirectPath_rejectsVaierService() {
        assertThrows(IllegalArgumentException.class,
            () -> service.setRootRedirectPath("vaier.example.com", "/admin/"));

        verify(forPersistingReverseProxyRoutes, never()).setRouteRootRedirectPath(anyString(), anyString());
    }

    @Test
    void setRootRedirectPath_rejectsAuthService() {
        assertThrows(IllegalArgumentException.class,
            () -> service.setRootRedirectPath("login.example.com", "/admin/"));

        verify(forPersistingReverseProxyRoutes, never()).setRouteRootRedirectPath(anyString(), anyString());
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

    private void setupDnsRecord(String name, DnsRecordType type) {
        DnsZone zone = new DnsZone("example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone))
            .thenReturn(List.of(new DnsRecord(name, type, 300L, List.of())));
    }

    private void setupNoDnsRecords() {
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
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

    private ReverseProxyRoute routeWithDomain(String domain) {
        return new ReverseProxyRoute("route", domain, "10.0.0.1", 8080, "svc", null);
    }

    // --- getPublishableServices helpers ---

    private PeerContainers unreachablePeer(String name, String ip) {
        return new PeerContainers(name, ip, "UNREACHABLE", List.of(), false, "");
    }

    private PeerContainers okPeer(String name, String ip, List<DockerService> containers) {
        return new PeerContainers(name, ip, "OK", containers, false, "");
    }

    private DiscoverLanServerContainersUseCase.LanServerContainers okLanHost(
            String hostName, String hostIp, int port, String relayPeerName,
            List<DockerService> containers) {
        return new DiscoverLanServerContainersUseCase.LanServerContainers(
            hostName, hostIp, port, relayPeerName, "OK", containers);
    }

    private DockerService peerContainer(String name, int port, String type) {
        return new DockerService("id", name, "image", "latest",
            List.of(new PortMapping(port, port, type, "0.0.0.0")), List.of(), "running");
    }

    private DockerService peerContainerWithNullPublicPort(String name, int privatePort, String type) {
        return new DockerService("id", name, "image", "latest",
            List.of(new PortMapping(privatePort, null, type, "0.0.0.0")), List.of(), "running");
    }

    private PublishableService localService(String name, int port) {
        return new PublishableService(PublishableSource.LOCAL, null, name, name, port, null, false);
    }

    private ReverseProxyRoute routeForPublishable(String address, int port) {
        return new ReverseProxyRoute("r", "svc.example.com", address, port, "svc", null);
    }
}
