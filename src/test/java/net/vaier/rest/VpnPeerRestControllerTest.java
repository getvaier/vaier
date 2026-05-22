package net.vaier.rest;

import net.vaier.adapter.driven.SseEventPublisher;
import net.vaier.application.CreatePeerUseCase;
import net.vaier.application.DeletePeerUseCase;
import net.vaier.application.GenerateDockerComposeUseCase;
import net.vaier.application.GeneratePeerSetupScriptUseCase;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetServerLocationUseCase;
import net.vaier.application.GetServerLocationUseCase.ServerLocation;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.RenamePeerUseCase;
import net.vaier.application.ResolveVpnPeerNameUseCase;
import net.vaier.application.UpdateLanCidrUseCase;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.MachineType;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VpnPeerRestControllerTest {

    @Mock GetVpnClientsUseCase vpnClientService;
    @Mock ResolveVpnPeerNameUseCase peerNameResolver;
    @Mock GetPeerConfigUseCase getPeerConfigUseCase;
    @Mock CreatePeerUseCase createPeerUseCase;
    @Mock DeletePeerUseCase deletePeerUseCase;
    @Mock GenerateDockerComposeUseCase generateDockerComposeUseCase;
    @Mock GeneratePeerSetupScriptUseCase generatePeerSetupScriptUseCase;
    @Mock UpdateLanCidrUseCase updateLanCidrUseCase;
    @Mock RenamePeerUseCase renamePeerUseCase;
    @Mock ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    @Mock SseEventPublisher sseEventPublisher;
    @Mock ForGeolocatingIps forGeolocatingIps;
    @Mock GetServerLocationUseCase getServerLocationUseCase;

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
        doThrow(new net.vaier.domain.PeerNotFoundException("Peer not found: ghost"))
            .when(forUpdatingPeerConfigurations).updateLanAddress("ghost", "192.168.3.121");
        var request = new VpnPeerRestController.UpdateLanAddressRequest("192.168.3.121");

        var response = controller.updateLanAddress("ghost", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(sseEventPublisher, never()).publish(org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void updateLanCidr_updatesAndReturnsNoContent() {
        var request = new VpnPeerRestController.UpdateLanCidrRequest("192.168.3.0/24");

        var response = controller.updateLanCidr("apalveien5", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(updateLanCidrUseCase).updateLanCidr("apalveien5", "192.168.3.0/24");
        verify(sseEventPublisher).publish("vpn-peers", "peers-updated", "");
    }

    @Test
    void updateLanCidr_blankClearsLanCidr() {
        var request = new VpnPeerRestController.UpdateLanCidrRequest("");

        var response = controller.updateLanCidr("apalveien5", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(updateLanCidrUseCase).updateLanCidr("apalveien5", "");
    }

    @Test
    void updateLanCidr_nullBodyIsTreatedAsClear() {
        var response = controller.updateLanCidr("apalveien5", null);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(updateLanCidrUseCase).updateLanCidr("apalveien5", null);
    }

    @Test
    void updateLanCidr_returns404WhenPeerNotFound() {
        doThrow(new net.vaier.domain.PeerNotFoundException("Peer not found: ghost"))
            .when(updateLanCidrUseCase).updateLanCidr("ghost", "192.168.3.0/24");
        var request = new VpnPeerRestController.UpdateLanCidrRequest("192.168.3.0/24");

        var response = controller.updateLanCidr("ghost", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(sseEventPublisher, never()).publish(org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void updateLanCidr_returns409WhenAnotherPeerOwnsTheCidr() {
        doThrow(new IllegalStateException("LAN CIDR 192.168.3.0/24 already owned by peer nuc02"))
            .when(updateLanCidrUseCase).updateLanCidr("apalveien5", "192.168.3.0/24");
        var request = new VpnPeerRestController.UpdateLanCidrRequest("192.168.3.0/24");

        var response = controller.updateLanCidr("apalveien5", request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        verify(sseEventPublisher, never()).publish(org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void renamePeer_renamesAndReturnsNoContent() {
        var request = new VpnPeerRestController.RenamePeerRequest("workstation");

        var response = controller.renamePeer("laptop", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(renamePeerUseCase).renamePeer("laptop", "workstation");
        verify(sseEventPublisher).publish("vpn-peers", "peers-updated", "");
    }

    @Test
    void renamePeer_returns404WhenPeerNotFound() {
        doThrow(new net.vaier.domain.PeerNotFoundException("Peer not found: ghost"))
            .when(renamePeerUseCase).renamePeer("ghost", "phantom");

        var response = controller.renamePeer("ghost", new VpnPeerRestController.RenamePeerRequest("phantom"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(sseEventPublisher, never()).publish(org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void renamePeer_returns409WhenNewNameAlreadyTaken() {
        doThrow(new IllegalStateException("A peer named desktop already exists"))
            .when(renamePeerUseCase).renamePeer("laptop", "desktop");

        var response = controller.renamePeer("laptop", new VpnPeerRestController.RenamePeerRequest("desktop"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void renamePeer_returns400WhenNewNameInvalid() {
        doThrow(new IllegalArgumentException("New peer name is empty after sanitisation"))
            .when(renamePeerUseCase).renamePeer("laptop", "   ");

        var response = controller.renamePeer("laptop", new VpnPeerRestController.RenamePeerRequest("   "));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void createPeer_passesDescriptionToUseCase() {
        var created = new CreatePeerUseCase.CreatedPeerUco(
                "nas", "nas", "10.13.13.5", "pub", "priv", "[Interface]", MachineType.UBUNTU_SERVER);
        when(createPeerUseCase.createPeer("nas", MachineType.UBUNTU_SERVER, null, null, "Home media server"))
                .thenReturn(created);
        var request = new VpnPeerRestController.CreatePeerRequest(
                "nas", MachineType.UBUNTU_SERVER, null, null, "Home media server");

        var response = controller.createPeer(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(createPeerUseCase).createPeer("nas", MachineType.UBUNTU_SERVER, null, null, "Home media server");
    }

    @Test
    void listPeers_includesPeerDescription() {
        VpnClient client = new VpnClient("pubkey", "10.13.13.2/32", "", "", "0", "0", "0");
        when(vpnClientService.getClients()).thenReturn(List.of(client));
        when(peerNameResolver.resolvePeerNameByIp("10.13.13.2")).thenReturn("nas");
        when(getPeerConfigUseCase.getPeerConfigByIp("10.13.13.2")).thenReturn(
                Optional.of(new GetPeerConfigUseCase.PeerConfigResult(
                        "nas", "nas", "10.13.13.2", "", MachineType.UBUNTU_SERVER, null, null, "Home media server")));

        var peer = controller.listPeers().getBody().get(0);

        assertThat(peer.description()).isEqualTo("Home media server");
    }

    @Test
    void updateDescription_updatesAndReturnsNoContent() {
        var request = new VpnPeerRestController.UpdateDescriptionRequest("Home media server");

        var response = controller.updateDescription("nas", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(forUpdatingPeerConfigurations).updateDescription("nas", "Home media server");
        verify(sseEventPublisher).publish("vpn-peers", "peers-updated", "");
    }

    @Test
    void updateDescription_nullBodyIsTreatedAsClear() {
        var response = controller.updateDescription("nas", null);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(forUpdatingPeerConfigurations).updateDescription("nas", null);
    }

    @Test
    void updateDescription_returns404WhenPeerNotFound() {
        doThrow(new net.vaier.domain.PeerNotFoundException("Peer not found: ghost"))
            .when(forUpdatingPeerConfigurations).updateDescription("ghost", "anything");
        var request = new VpnPeerRestController.UpdateDescriptionRequest("anything");

        var response = controller.updateDescription("ghost", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(sseEventPublisher, never()).publish(org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void listPeers_includesGeolocationFieldsWhenLookupSucceeds() {
        VpnClient client = new VpnClient("pubkey", "10.13.13.2/32", "203.0.113.10", "51820", "0", "0", "0");
        when(vpnClientService.getClients()).thenReturn(List.of(client));
        when(peerNameResolver.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(getPeerConfigUseCase.getPeerConfigByIp("10.13.13.2")).thenReturn(Optional.empty());
        when(forGeolocatingIps.locate("203.0.113.10"))
            .thenReturn(Optional.of(new GeoLocation(59.91, 10.74, "Oslo", "Norway")));

        var response = controller.listPeers();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = response.getBody();
        assertThat(body).hasSize(1);
        var peer = body.get(0);
        assertThat(peer.name()).isEqualTo("alice");
        assertThat(peer.latitude()).isEqualTo(59.91);
        assertThat(peer.longitude()).isEqualTo(10.74);
        assertThat(peer.city()).isEqualTo("Oslo");
        assertThat(peer.country()).isEqualTo("Norway");
    }

    @Test
    void listPeers_geolocationFieldsAreNullWhenLookupFails() {
        VpnClient client = new VpnClient("pubkey", "10.13.13.2/32", "203.0.113.10", "51820", "0", "0", "0");
        when(vpnClientService.getClients()).thenReturn(List.of(client));
        when(peerNameResolver.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(getPeerConfigUseCase.getPeerConfigByIp("10.13.13.2")).thenReturn(Optional.empty());
        when(forGeolocatingIps.locate("203.0.113.10")).thenReturn(Optional.empty());

        var peer = controller.listPeers().getBody().get(0);

        assertThat(peer.latitude()).isNull();
        assertThat(peer.longitude()).isNull();
        assertThat(peer.city()).isNull();
        assertThat(peer.country()).isNull();
    }

    @Test
    void listPeers_skipsGeolocationLookupWhenEndpointIsBlank() {
        VpnClient client = new VpnClient("pubkey", "10.13.13.2/32", "", "", "0", "0", "0");
        when(vpnClientService.getClients()).thenReturn(List.of(client));
        when(peerNameResolver.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(getPeerConfigUseCase.getPeerConfigByIp("10.13.13.2")).thenReturn(Optional.empty());

        var peer = controller.listPeers().getBody().get(0);

        assertThat(peer.latitude()).isNull();
        assertThat(peer.longitude()).isNull();
        verify(forGeolocatingIps, never()).locate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void getServerLocation_returnsLocationWhenResolved() {
        when(getServerLocationUseCase.getServerLocation())
            .thenReturn(Optional.of(new ServerLocation("vaier.example.com", 59.91, 10.74, "Oslo", "Norway")));

        var response = controller.getServerLocation();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = response.getBody();
        assertThat(body.publicHost()).isEqualTo("vaier.example.com");
        assertThat(body.latitude()).isEqualTo(59.91);
        assertThat(body.longitude()).isEqualTo(10.74);
        assertThat(body.city()).isEqualTo("Oslo");
        assertThat(body.country()).isEqualTo("Norway");
    }

    @Test
    void getServerLocation_returns404WhenUnavailable() {
        when(getServerLocationUseCase.getServerLocation()).thenReturn(Optional.empty());

        var response = controller.getServerLocation();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
