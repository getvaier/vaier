package net.vaier.application.service;

import net.vaier.application.ForInvalidatingPublishedServicesCache;
import net.vaier.application.ForPublishingEvents;
import net.vaier.application.PublishPeerServiceUseCase.PendingPublication;
import net.vaier.application.PublishPeerServiceUseCase.PublishStatus;
import net.vaier.domain.DnsRecord;
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

import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublishPeerServiceServiceTest {

    @Mock
    ForPersistingDnsRecords forPersistingDnsRecords;

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Mock
    ForPublishingEvents forPublishingEvents;

    @Mock
    PendingPublicationsTracker pendingPublicationsTracker;

    @Mock
    ForInvalidatingPublishedServicesCache forInvalidatingPublishedServicesCache;

    @InjectMocks
    PublishPeerServiceService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "vaierDomain", "example.com");
    }

    @Test
    void publishService_createsCnameDnsRecord() {
        service.publishService("10.0.0.1", 8080, "app", false, null);

        ArgumentCaptor<DnsRecord> recordCaptor = ArgumentCaptor.forClass(DnsRecord.class);
        verify(forPersistingDnsRecords).addDnsRecord(recordCaptor.capture(), any());

        DnsRecord record = recordCaptor.getValue();
        assertThat(record.name()).isEqualTo("app.example.com.");
        assertThat(record.type()).isEqualTo(net.vaier.domain.DnsRecord.DnsRecordType.CNAME);
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

        service.publishService("10.0.0.1", 8080, "app", false, null);

        // Immediately after publish, route not yet in Traefik → not in routes, but in pending with (false, false)
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
        service.publishService("10.0.0.1", 8080, "app", false, null);

        verify(forPublishingEvents).publish("published-services", "publish-dns-created", "app");
    }

    @Test
    void getPendingPublications_afterPublish_returnsEntry() {
        service.publishService("10.0.0.1", 8080, "app", true, null);
        List<PendingPublication> pending = service.getPendingPublications();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).subdomain()).isEqualTo("app");
        assertThat(pending.get(0).requiresAuth()).isTrue();
        assertThat(pending.get(0).dnsPropagated()).isFalse();
    }

    @Test
    void waitForDnsThenActivate_waitsForTraefikRouteBeforeFiringEvent() {
        // DNS resolves immediately
        ReflectionTestUtils.setField(service, "dnsChecker", (Predicate<String>) fqdn -> true);
        // First getReverseProxyRoutes call: Traefik not yet loaded; second call: route present
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of())
            .thenReturn(List.of(routeWithDomain("app.example.com")));

        service.waitForDnsThenActivate("app", "app.example.com", "10.0.0.1", 8080, false, null);

        InOrder inOrder = inOrder(forPersistingReverseProxyRoutes, forPublishingEvents);
        inOrder.verify(forPersistingReverseProxyRoutes).addReverseProxyRoute("app.example.com", "10.0.0.1", 8080, false, null);
        inOrder.verify(forPublishingEvents).publish("published-services", "publish-traefik-active", "app");
        verify(forPersistingReverseProxyRoutes, atLeast(2)).getReverseProxyRoutes();
    }

    @Test
    void waitForDnsThenActivate_invalidatesPublishedServicesCacheAfterActivation() {
        ReflectionTestUtils.setField(service, "dnsChecker", (Predicate<String>) fqdn -> true);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes())
            .thenReturn(List.of(routeWithDomain("app.example.com")));

        service.waitForDnsThenActivate("app", "app.example.com", "10.0.0.1", 8080, false, null);

        verify(forInvalidatingPublishedServicesCache).invalidatePublishedServicesCache();
    }

    private ReverseProxyRoute routeWithDomain(String domain) {
        return new ReverseProxyRoute("route", domain, "10.0.0.1", 8080, "svc", null);
    }
}
