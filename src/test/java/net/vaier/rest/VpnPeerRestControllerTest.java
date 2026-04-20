package net.vaier.rest;

import net.vaier.adapter.driven.SseEventPublisher;
import net.vaier.application.CreatePeerUseCase;
import net.vaier.application.DeletePeerUseCase;
import net.vaier.application.GenerateDockerComposeUseCase;
import net.vaier.application.GeneratePeerSetupScriptUseCase;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.ResolveVpnPeerNameUseCase;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class VpnPeerRestControllerTest {

    @Mock GetVpnClientsUseCase vpnClientService;
    @Mock ResolveVpnPeerNameUseCase peerNameResolver;
    @Mock GetPeerConfigUseCase getPeerConfigUseCase;
    @Mock CreatePeerUseCase createPeerUseCase;
    @Mock DeletePeerUseCase deletePeerUseCase;
    @Mock GenerateDockerComposeUseCase generateDockerComposeUseCase;
    @Mock GeneratePeerSetupScriptUseCase generatePeerSetupScriptUseCase;
    @Mock ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    @Mock SseEventPublisher sseEventPublisher;

    @InjectMocks VpnPeerRestController controller;

    @Test
    void updateLanAddress_updatesAndReturnsNoContent() {
        var request = new VpnPeerRestController.UpdateLanAddressRequest("192.168.3.121");

        var response = controller.updateLanAddress("apalveien5", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(forUpdatingPeerConfigurations).updateLanAddress("apalveien5", "192.168.3.121");
        verify(sseEventPublisher).publish("vpn-peers", "peers-updated", "");
    }

    @Test
    void updateLanAddress_blankClearsLanAddress() {
        var request = new VpnPeerRestController.UpdateLanAddressRequest("");

        var response = controller.updateLanAddress("apalveien5", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(forUpdatingPeerConfigurations).updateLanAddress("apalveien5", "");
    }

    @Test
    void updateLanAddress_nullBodyIsTreatedAsClear() {
        var response = controller.updateLanAddress("apalveien5", null);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(forUpdatingPeerConfigurations).updateLanAddress("apalveien5", null);
    }

    @Test
    void updateLanAddress_returns404WhenPeerNotFound() {
        doThrow(new IllegalArgumentException("Peer not found: ghost"))
            .when(forUpdatingPeerConfigurations).updateLanAddress("ghost", "192.168.3.121");
        var request = new VpnPeerRestController.UpdateLanAddressRequest("192.168.3.121");

        var response = controller.updateLanAddress("ghost", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(sseEventPublisher, never()).publish(org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString());
    }
}
