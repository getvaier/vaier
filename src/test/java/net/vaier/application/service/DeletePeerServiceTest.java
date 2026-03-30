package net.vaier.application.service;

import net.vaier.domain.port.ForDeletingVpnPeers;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeletePeerServiceTest {

    @Mock
    ForResolvingPeerNames peerNameResolver;

    @Mock
    ForDeletingVpnPeers vpnPeerDeleter;

    @InjectMocks
    DeletePeerService service;

    @Test
    void deletePeer_byName_deletesDirectlyWithoutResolving() {
        service.deletePeer("wg0", "alice");

        verify(vpnPeerDeleter).deletePeer("wg0", "alice");
        verifyNoInteractions(peerNameResolver);
    }

    @Test
    void deletePeer_byIp_resolvesToNameBeforeDeleting() {
        when(peerNameResolver.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");

        service.deletePeer("wg0", "10.13.13.2");

        verify(peerNameResolver).resolvePeerNameByIp("10.13.13.2");
        verify(vpnPeerDeleter).deletePeer("wg0", "alice");
    }

    @Test
    void deletePeer_ipNotResolved_throwsIllegalArgumentException() {
        when(peerNameResolver.resolvePeerNameByIp("10.13.13.99")).thenReturn("10.13.13.99");

        assertThatThrownBy(() -> service.deletePeer("wg0", "10.13.13.99"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("10.13.13.99");
    }

    @Test
    void deletePeer_ipNotResolved_doesNotCallDeleter() {
        when(peerNameResolver.resolvePeerNameByIp("10.13.13.99")).thenReturn("10.13.13.99");

        assertThatThrownBy(() -> service.deletePeer("wg0", "10.13.13.99"))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(vpnPeerDeleter);
    }

    @Test
    void deletePeer_ipLikeStringWithHighOctets_matchesRegexAndAttemptsResolution() {
        // Pattern is \d+\.\d+\.\d+\.\d+ — matches even invalid IPs like 999.999.999.999
        when(peerNameResolver.resolvePeerNameByIp("999.999.999.999")).thenReturn("peer-x");

        service.deletePeer("wg0", "999.999.999.999");

        verify(vpnPeerDeleter).deletePeer("wg0", "peer-x");
    }
}
