package net.vaier.application.service;

import net.vaier.application.GetPeerConfigUseCase.PeerConfigResult;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GetPeerConfigServiceTest {

    @Mock
    ForGettingPeerConfigurations peerConfigProvider;

    @InjectMocks
    GetPeerConfigService service;

    @Test
    void getPeerConfig_byName_callsGetByName() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "[Interface]\nAddress=10.13.13.2/32"))
        );

        Optional<PeerConfigResult> result = service.getPeerConfig("alice");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("alice");
        assertThat(result.get().ipAddress()).isEqualTo("10.13.13.2");
        verify(peerConfigProvider).getPeerConfigByName("alice");
        verifyNoMoreInteractions(peerConfigProvider);
    }

    @Test
    void getPeerConfig_byIp_callsGetByIp() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.2")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config"))
        );

        Optional<PeerConfigResult> result = service.getPeerConfig("10.13.13.2");

        assertThat(result).isPresent();
        verify(peerConfigProvider).getPeerConfigByIp("10.13.13.2");
        verifyNoMoreInteractions(peerConfigProvider);
    }

    @Test
    void getPeerConfig_notFound_returnsEmpty() {
        when(peerConfigProvider.getPeerConfigByName("nobody")).thenReturn(Optional.empty());

        assertThat(service.getPeerConfig("nobody")).isEmpty();
    }

    @Test
    void getPeerConfig_mapsPeerConfigurationFieldsCorrectly() {
        when(peerConfigProvider.getPeerConfigByName("bob")).thenReturn(
            Optional.of(new PeerConfiguration("bob", "10.13.13.3", "wg-config-content"))
        );

        PeerConfigResult result = service.getPeerConfig("bob").orElseThrow();

        assertThat(result.name()).isEqualTo("bob");
        assertThat(result.ipAddress()).isEqualTo("10.13.13.3");
        assertThat(result.configContent()).isEqualTo("wg-config-content");
    }

    @Test
    void getPeerConfigByIp_delegatesToPort() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.2")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config"))
        );

        Optional<PeerConfigResult> result = service.getPeerConfigByIp("10.13.13.2");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("alice");
        verify(peerConfigProvider).getPeerConfigByIp("10.13.13.2");
    }

    @Test
    void getPeerConfigByIp_returnsEmptyWhenNotFound() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.99")).thenReturn(Optional.empty());

        assertThat(service.getPeerConfigByIp("10.13.13.99")).isEmpty();
    }
}
