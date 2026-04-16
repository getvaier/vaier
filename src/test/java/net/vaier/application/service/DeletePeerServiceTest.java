package net.vaier.application.service;

import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForDeletingVpnPeers;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeletePeerServiceTest {

    @Mock
    ForResolvingPeerNames peerNameResolver;

    @Mock
    ForDeletingVpnPeers vpnPeerDeleter;

    @Mock
    ForGettingPeerConfigurations peerConfigProvider;

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Mock
    DeletePublishedServiceUseCase deletePublishedServiceUseCase;

    @InjectMocks
    DeletePeerService service;

    @Test
    void deletePeer_byName_deletesDirectlyWithoutResolving() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(Optional.empty());

        service.deletePeer("alice");

        verify(vpnPeerDeleter).deletePeer("alice");
        verifyNoInteractions(peerNameResolver);
    }

    @Test
    void deletePeer_byIp_resolvesToNameBeforeDeleting() {
        when(peerNameResolver.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(Optional.empty());

        service.deletePeer("10.13.13.2");

        verify(peerNameResolver).resolvePeerNameByIp("10.13.13.2");
        verify(vpnPeerDeleter).deletePeer("alice");
    }

    @Test
    void deletePeer_ipNotResolved_throwsIllegalArgumentException() {
        when(peerNameResolver.resolvePeerNameByIp("10.13.13.99")).thenReturn("10.13.13.99");

        assertThatThrownBy(() -> service.deletePeer("10.13.13.99"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("10.13.13.99");
    }

    @Test
    void deletePeer_ipNotResolved_doesNotCallDeleter() {
        when(peerNameResolver.resolvePeerNameByIp("10.13.13.99")).thenReturn("10.13.13.99");

        assertThatThrownBy(() -> service.deletePeer("10.13.13.99"))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(vpnPeerDeleter);
    }

    @Test
    void deletePeer_ipLikeStringWithHighOctets_matchesRegexAndAttemptsResolution() {
        when(peerNameResolver.resolvePeerNameByIp("999.999.999.999")).thenReturn("peer-x");
        when(peerConfigProvider.getPeerConfigByName("peer-x")).thenReturn(Optional.empty());

        service.deletePeer("999.999.999.999");

        verify(vpnPeerDeleter).deletePeer("peer-x");
    }

    @Test
    void deletePeer_deletesPublishedServicesPointingToPeerIp() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute peerRoute = new ReverseProxyRoute("app-router", "app.example.com", "10.13.13.2", 8080, "app-service", null);
        ReverseProxyRoute otherRoute = new ReverseProxyRoute("other-router", "other.example.com", "10.13.13.3", 9090, "other-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(peerRoute, otherRoute));

        service.deletePeer("alice");

        verify(deletePublishedServiceUseCase).deleteService("app.example.com");
        verify(deletePublishedServiceUseCase, never()).deleteService("other.example.com");
    }

    @Test
    void deletePeer_deletesMultipleServicesPointingToSamePeerIp() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute route1 = new ReverseProxyRoute("app1-router", "app1.example.com", "10.13.13.2", 8080, "app1-service", null);
        ReverseProxyRoute route2 = new ReverseProxyRoute("app2-router", "app2.example.com", "10.13.13.2", 9090, "app2-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route1, route2));

        service.deletePeer("alice");

        verify(deletePublishedServiceUseCase).deleteService("app1.example.com");
        verify(deletePublishedServiceUseCase).deleteService("app2.example.com");
    }

    @Test
    void deletePeer_noPublishedServicesForPeer_stillDeletesPeer() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        service.deletePeer("alice");

        verify(vpnPeerDeleter).deletePeer("alice");
        verifyNoInteractions(deletePublishedServiceUseCase);
    }

    @Test
    void deletePeer_peerConfigNotFound_stillDeletesPeerWithoutCleaningServices() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(Optional.empty());

        service.deletePeer("alice");

        verify(vpnPeerDeleter).deletePeer("alice");
        verifyNoInteractions(deletePublishedServiceUseCase);
    }

    @Test
    void deletePeer_byIp_usesResolvedIpForServiceCleanup() {
        when(peerNameResolver.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute peerRoute = new ReverseProxyRoute("app-router", "app.example.com", "10.13.13.2", 8080, "app-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(peerRoute));

        service.deletePeer("10.13.13.2");

        verify(deletePublishedServiceUseCase).deleteService("app.example.com");
        verify(vpnPeerDeleter).deletePeer("alice");
    }

    @Test
    void deletePeer_deletesServicesBeforeDeletingPeer() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute peerRoute = new ReverseProxyRoute("app-router", "app.example.com", "10.13.13.2", 8080, "app-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(peerRoute));

        service.deletePeer("alice");

        var order = inOrder(deletePublishedServiceUseCase, vpnPeerDeleter);
        order.verify(deletePublishedServiceUseCase).deleteService("app.example.com");
        order.verify(vpnPeerDeleter).deletePeer("alice");
    }
}
