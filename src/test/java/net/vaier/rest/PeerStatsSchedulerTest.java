package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.application.ForPublishingEvents;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.ResolveVpnPeerNameUseCase;
import net.vaier.domain.VpnClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class PeerStatsSchedulerTest {

    GetVpnClientsUseCase vpnClients;
    ResolveVpnPeerNameUseCase peerNameResolver;
    ForPublishingEvents eventPublisher;
    PeerStatsScheduler scheduler;

    @BeforeEach
    void setUp() {
        vpnClients = mock(GetVpnClientsUseCase.class);
        peerNameResolver = mock(ResolveVpnPeerNameUseCase.class);
        eventPublisher = mock(ForPublishingEvents.class);
        scheduler = new PeerStatsScheduler(vpnClients, peerNameResolver, eventPublisher, new ObjectMapper());
    }

    @Test
    void publishPeerStats_publishesPeersStatsEvent() {
        when(vpnClients.getClients()).thenReturn(List.of(
                new VpnClient("pubkey1", "10.0.0.2/32", "1.2.3.4", "51820", "1700000000", "1024", "2048")
        ));
        when(peerNameResolver.resolvePeerNameByIp("10.0.0.2")).thenReturn("myserver");

        scheduler.publishPeerStats();

        verify(eventPublisher).publish(eq("vpn-peers"), eq("peers-stats"), contains("myserver"));
    }

    @Test
    void publishPeerStats_whenFetchFails_doesNotThrow() {
        when(vpnClients.getClients()).thenThrow(new RuntimeException("wg unavailable"));

        org.assertj.core.api.Assertions.assertThatCode(() -> scheduler.publishPeerStats())
                .doesNotThrowAnyException();
    }

    @Test
    void publishPeerStats_noPeers_publishesEmptyArray() {
        when(vpnClients.getClients()).thenReturn(List.of());

        scheduler.publishPeerStats();

        verify(eventPublisher).publish(eq("vpn-peers"), eq("peers-stats"), eq("[]"));
    }
}
