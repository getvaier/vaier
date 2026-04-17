package net.vaier.application.service;

import net.vaier.application.PublishedServicesCacheInvalidator;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;



@ExtendWith(MockitoExtension.class)
class DeletePublishedServiceServiceTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Mock
    ForPersistingDnsRecords forPersistingDnsRecords;

    @Mock
    PublishedServicesCacheInvalidator publishedServicesCacheInvalidator;

    @Mock
    ConfigResolver configResolver;

    @InjectMocks
    DeletePublishedServiceService service;

    @BeforeEach
    void setUp() {
        when(configResolver.getDomain()).thenReturn("example.com");
    }

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
        // route present → empty (transitional) → empty (stable): needs 2 consecutive absences
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
            .thenReturn(List.of()); // second absence → stable

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
        service.deleteService("app.example.com");

        verify(publishedServicesCacheInvalidator).invalidatePublishedServicesCache();
    }
}
