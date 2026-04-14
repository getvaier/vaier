package net.vaier.application.service;

import net.vaier.application.ForInvalidatingPublishedServicesCache;
import net.vaier.application.ForPublishingEvents;
import net.vaier.application.UpdatePublishedServiceDnsUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UpdatePublishedServiceDnsServiceTest {

    @Mock
    ForPersistingDnsRecords forPersistingDnsRecords;

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Mock
    ForPublishingEvents forPublishingEvents;

    @Mock
    ForInvalidatingPublishedServicesCache forInvalidatingPublishedServicesCache;

    @Mock
    ConfigResolver configResolver;

    @InjectMocks
    UpdatePublishedServiceDnsService service;

    @BeforeEach
    void setUp() {
        lenient().when(configResolver.getDomain()).thenReturn("example.com");
        // Speed up DNS polling for tests
        ReflectionTestUtils.setField(service, "dnsTimeoutMillis", 100L);
        ReflectionTestUtils.setField(service, "dnsRetryIntervalMillis", 10L);
        // DNS resolves immediately by default
        ReflectionTestUtils.setField(service, "dnsChecker", (Predicate<String>) fqdn -> true);
    }

    @Test
    void updateDns_mandatory_vaier_throws() {
        String oldFqdn = "vaier.example.com";
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain(oldFqdn)));

        assertThatThrownBy(() -> service.updateDns(oldFqdn, "newapp"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vaier");
    }

    @Test
    void updateDns_mandatory_login_throws() {
        String oldFqdn = "login.example.com";
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain(oldFqdn)));

        assertThatThrownBy(() -> service.updateDns(oldFqdn, "newapp"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("login");
    }

    @Test
    void updateDns_routeNotFound_throws() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateDns("app.example.com", "newapp"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void updateDns_newSubdomainAlreadyInUse_throws() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(
                routeWithDomain("app.example.com"),
                routeWithDomain("newapp.example.com")
            ));

        assertThatThrownBy(() -> service.updateDns("app.example.com", "newapp"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already in use");
    }

    @Test
    void updateDns_createsNewCnameDnsRecord() {
        ReverseProxyRoute existingRoute = routeWithDomain("app.example.com");
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(existingRoute));

        service.updateDns("app.example.com", "newapp");

        ArgumentCaptor<DnsRecord> recordCaptor = ArgumentCaptor.forClass(DnsRecord.class);
        verify(forPersistingDnsRecords).addDnsRecord(recordCaptor.capture(), any());
        DnsRecord added = recordCaptor.getValue();
        assertThat(added.name()).isEqualTo("newapp.example.com.");
        assertThat(added.type()).isEqualTo(DnsRecordType.CNAME);
        assertThat(added.values()).containsExactly("vaier.example.com.");
    }

    @Test
    void updateDns_withGroupPrefix_preservesGroupInNewFqdn() {
        ReverseProxyRoute existingRoute = routeWithDomain("openhab.colina27.example.com");
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(existingRoute));

        service.updateDns("openhab.colina27.example.com", "open");

        ArgumentCaptor<DnsRecord> recordCaptor = ArgumentCaptor.forClass(DnsRecord.class);
        verify(forPersistingDnsRecords).addDnsRecord(recordCaptor.capture(), any());
        DnsRecord added = recordCaptor.getValue();
        assertThat(added.name()).isEqualTo("open.colina27.example.com.");
    }

    @Test
    void waitForDnsThenSwitch_replacesTraefikRouteWithNewFqdn() {
        ReverseProxyRoute existingRoute = routeWithDomain("app.example.com");

        service.waitForDnsThenSwitch(existingRoute, "app.example.com", "newapp.example.com", "example.com");

        verify(forPersistingReverseProxyRoutes).deleteReverseProxyRoute(existingRoute.getName());
        verify(forPersistingReverseProxyRoutes).addReverseProxyRoute(
            eq("newapp.example.com"), eq("10.0.0.1"), eq(8080), anyBoolean(), any()
        );
    }

    @Test
    void waitForDnsThenSwitch_deletesOldCnameDnsRecord() {
        ReverseProxyRoute existingRoute = routeWithDomain("app.example.com");

        service.waitForDnsThenSwitch(existingRoute, "app.example.com", "newapp.example.com", "example.com");

        verify(forPersistingDnsRecords).deleteDnsRecord(
            eq("app.example.com"),
            eq(DnsRecordType.CNAME),
            any(DnsZone.class)
        );
    }

    @Test
    void waitForDnsThenSwitch_dnsTimeout_doesNotUpdateTraefik() {
        ReflectionTestUtils.setField(service, "dnsChecker", (Predicate<String>) fqdn -> false);
        ReverseProxyRoute existingRoute = routeWithDomain("app.example.com");

        service.waitForDnsThenSwitch(existingRoute, "app.example.com", "newapp.example.com", "example.com");

        verify(forPersistingReverseProxyRoutes, never()).deleteReverseProxyRoute(any());
        verify(forPersistingReverseProxyRoutes, never()).addReverseProxyRoute(any(), any(), anyInt(), anyBoolean(), any());
        verify(forPersistingDnsRecords, never()).deleteDnsRecord(any(), any(), any());
    }

    @Test
    void waitForDnsThenSwitch_invalidatesPublishedServicesCacheOnSuccess() {
        ReverseProxyRoute existingRoute = routeWithDomain("app.example.com");

        service.waitForDnsThenSwitch(existingRoute, "app.example.com", "newapp.example.com", "example.com");

        verify(forInvalidatingPublishedServicesCache).invalidatePublishedServicesCache();
    }

    @Test
    void waitForDnsThenSwitch_publishesServiceUpdatedEvent() {
        ReverseProxyRoute existingRoute = routeWithDomain("app.example.com");

        service.waitForDnsThenSwitch(existingRoute, "app.example.com", "newapp.example.com", "example.com");

        verify(forPublishingEvents).publish("published-services", "service-updated", "newapp.example.com");
    }

    private ReverseProxyRoute routeWithDomain(String domain) {
        return new ReverseProxyRoute("route-" + domain, domain, "10.0.0.1", 8080, "svc", null);
    }
}
