package net.vaier.application.service;

import net.vaier.application.GetPublishedServicesUseCase.PublishedServiceUco;
import net.vaier.config.ServiceNames;
import net.vaier.domain.*;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.Server.State;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    PublishingService service;

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
    void getPublishedServices_addressFoundInLocalServices_hostStateOk() {
        setupOneRoute("app.example.com", "my-container", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(
            List.of(new DockerService("id", "my-container", "image", "latest",
                List.of(new DockerService.PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of()))
        );

        PublishedServiceUco result = service.getPublishedServices().get(0);

        assertThat(result.state()).isEqualTo(State.OK);
    }

    @Test
    void getPublishedServices_vpnPeerWithRecentHandshake_hostStateOk() {
        String recentHandshake = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        setupOneRoute("app.example.com", "10.13.13.2", 8080);
        setupNoDnsRecords();
        when(forGettingVpnClients.getClients()).thenReturn(
            List.of(new VpnClient("pubkey", "10.13.13.2/32", "1.2.3.4", "51820", recentHandshake, "0", "0"))
        );
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
    void getPublishedServices_regularService_mandatoryIsFalse() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        assertThat(service.getPublishedServices().get(0).mandatory()).isFalse();
    }

    @Test
    void getPublishedServices_vaierService_mandatoryIsTrue() {
        setupOneRoute(ServiceNames.VAIER + ".example.com", "10.0.0.1", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        assertThat(service.getPublishedServices().get(0).mandatory()).isTrue();
    }

    @Test
    void getPublishedServices_authService_mandatoryIsTrue() {
        setupOneRoute("login.example.com", "10.0.0.1", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        assertThat(service.getPublishedServices().get(0).mandatory()).isTrue();
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
}
