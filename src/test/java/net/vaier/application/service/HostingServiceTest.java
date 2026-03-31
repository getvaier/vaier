package net.vaier.application.service;

import net.vaier.application.GetHostedServicesUseCase.HostedServiceUco;
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
class HostingServiceTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Mock
    ForGettingServerInfo forGettingServerInfo;

    @Mock
    ForPersistingDnsRecords forPersistingDnsRecords;

    @Mock
    ForGettingVpnClients forGettingVpnClients;

    @InjectMocks
    HostingService service;

    @Test
    void getHostedServices_emptyRoutes_returnsEmptyWithoutCallingOtherPorts() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        assertThat(service.getHostedServices()).isEmpty();
        verifyNoInteractions(forPersistingDnsRecords, forGettingVpnClients, forGettingServerInfo);
    }

    @Test
    void getHostedServices_routeWithCnameRecord_dnsStateOk() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupDnsRecord("app.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        HostedServiceUco result = service.getHostedServices().get(0);

        assertThat(result.dnsState()).isEqualTo(DnsState.OK);
    }

    @Test
    void getHostedServices_routeWithARecord_dnsStateOk() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupDnsRecord("app.example.com", DnsRecordType.A);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        HostedServiceUco result = service.getHostedServices().get(0);

        assertThat(result.dnsState()).isEqualTo(DnsState.OK);
    }

    @Test
    void getHostedServices_routeWithNoMatchingDnsRecord_dnsStateNonExisting() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupDnsRecord("other.example.com", DnsRecordType.CNAME);
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        HostedServiceUco result = service.getHostedServices().get(0);

        assertThat(result.dnsState()).isEqualTo(DnsState.NON_EXISTING);
    }

    @Test
    void getHostedServices_routeWithNoDnsRecordsAtAll_dnsStateNonExisting() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        HostedServiceUco result = service.getHostedServices().get(0);

        assertThat(result.dnsState()).isEqualTo(DnsState.NON_EXISTING);
    }

    @Test
    void getHostedServices_addressFoundInLocalServices_hostStateOk() {
        setupOneRoute("app.example.com", "my-container", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(
            List.of(new DockerService("id", "my-container", "image",
                List.of(new DockerService.PortMapping(8080, 8080, "tcp", "0.0.0.0"))))
        );

        HostedServiceUco result = service.getHostedServices().get(0);

        assertThat(result.state()).isEqualTo(State.OK);
    }

    @Test
    void getHostedServices_addressMatchesVpnClientAllowedIps_hostStateOk() {
        setupOneRoute("app.example.com", "10.13.13.2", 8080);
        setupNoDnsRecords();
        when(forGettingVpnClients.getClients()).thenReturn(
            List.of(new VpnClient("pubkey", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0"))
        );
        setupEmptyLocalServices();

        HostedServiceUco result = service.getHostedServices().get(0);

        assertThat(result.state()).isEqualTo(State.OK);
    }

    @Test
    void getHostedServices_noLocalServiceAndNoVpnClientMatch_hostStateUnreachable() {
        setupOneRoute("app.example.com", "192.168.99.1", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        HostedServiceUco result = service.getHostedServices().get(0);

        assertThat(result.state()).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void getHostedServices_routeWithAuth_authenticatedIsTrue() {
        ReverseProxyRoute route = new ReverseProxyRoute(
            "route", "app.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", "admin", null)
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route));
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        assertThat(service.getHostedServices().get(0).authenticated()).isTrue();
    }

    @Test
    void getHostedServices_routeWithNoAuth_authenticatedIsFalse() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        assertThat(service.getHostedServices().get(0).authenticated()).isFalse();
    }

    @Test
    void getHostedServices_regularService_mandatoryIsFalse() {
        setupOneRoute("app.example.com", "10.0.0.1", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        assertThat(service.getHostedServices().get(0).mandatory()).isFalse();
    }

    @Test
    void getHostedServices_vaierService_mandatoryIsTrue() {
        setupOneRoute("vaier.example.com", "10.0.0.1", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        assertThat(service.getHostedServices().get(0).mandatory()).isTrue();
    }

    @Test
    void getHostedServices_authService_mandatoryIsTrue() {
        setupOneRoute("auth.example.com", "10.0.0.1", 8080);
        setupNoDnsRecords();
        setupEmptyVpnClients();
        setupEmptyLocalServices();

        assertThat(service.getHostedServices().get(0).mandatory()).isTrue();
    }

    @Test
    void getHostedServices_expensivePortsCalledExactlyOnce() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("app.example.com", "10.0.0.1", 8080),
            route("db.example.com", "10.0.0.2", 5432)
        ));
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.getHostedServices();

        verify(forPersistingDnsRecords, times(1)).getDnsZones();
        verify(forGettingVpnClients, times(1)).getClients();
        verify(forGettingServerInfo, times(1)).getServicesWithExposedPorts(any());
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
