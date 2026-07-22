package net.vaier.rest;

import net.vaier.domain.PeerNotFoundException;
import net.vaier.domain.ConflictException;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSubscribingToEvents;
import net.vaier.config.ConfigResolver;
import net.vaier.application.CreatePeerUseCase;
import net.vaier.application.DeletePeerUseCase;
import net.vaier.application.GenerateDockerComposeUseCase;
import net.vaier.application.GeneratePeerSetupScriptUseCase;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetServerLocationUseCase;
import net.vaier.application.GetServerLocationUseCase.ServerLocation;
import net.vaier.application.GetVpnPeersUseCase;
import net.vaier.application.GetVpnPeersUseCase.VpnPeerView;
import net.vaier.application.ReissuePeerConfigUseCase;
import net.vaier.application.RenamePeerUseCase;
import net.vaier.application.UpdateLanCidrUseCase;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.MachineType;
import net.vaier.domain.SetupToken;
import net.vaier.domain.port.ForTrackingPeerConfigRetrieval;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import net.vaier.domain.port.ForVendingSetupTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VpnPeerRestControllerTest {

    @Mock GetVpnPeersUseCase getVpnPeersUseCase;
    @Mock GetPeerConfigUseCase getPeerConfigUseCase;
    @Mock CreatePeerUseCase createPeerUseCase;
    @Mock DeletePeerUseCase deletePeerUseCase;
    @Mock GenerateDockerComposeUseCase generateDockerComposeUseCase;
    @Mock GeneratePeerSetupScriptUseCase generatePeerSetupScriptUseCase;
    @Mock UpdateLanCidrUseCase updateLanCidrUseCase;
    @Mock RenamePeerUseCase renamePeerUseCase;
    @Mock ReissuePeerConfigUseCase reissuePeerConfigUseCase;
    @Mock net.vaier.application.UpdatePeerDeviceCategoryUseCase updatePeerDeviceCategoryUseCase;
    @Mock ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    @Mock ForTrackingPeerConfigRetrieval forTrackingPeerConfigRetrieval;
    @Mock ForVendingSetupTokens forVendingSetupTokens;
    @Mock ForPublishingEvents forPublishingEvents;
    @Mock ForSubscribingToEvents forSubscribingToEvents;
    @Mock GetServerLocationUseCase getServerLocationUseCase;
    @Mock ConfigResolver configResolver;

    @InjectMocks VpnPeerRestController controller;

    private static VpnPeerView view(String id, String name, boolean connected,
                                    String endpointIp, MachineType type, String description,
                                    Optional<GeoLocation> geo) {
        return new VpnPeerView(id, name, "pub", "10.13.13.2/32", "10.13.13.2",
            endpointIp, "51820", "0", connected, "0", "0",
            type, type.isServerType(), type.isVpnPeer() && !type.isServerType(), false,
            net.vaier.domain.PeerArtifact.forPeerType(type),
            null, null, description, geo, false,
            net.vaier.domain.DeviceCategory.detect(name, type, null), false, type.isServerType());
    }

    @Test
    void subscribeToEvents_subscribesToVpnPeersTopicViaPort() {
        SseEmitter emitter = new SseEmitter();
        when(forSubscribingToEvents.subscribe("vpn-peers")).thenReturn(emitter);

        SseEmitter result = controller.subscribeToEvents();

        assertThat(result).isSameAs(emitter);
        verify(forSubscribingToEvents).subscribe("vpn-peers");
    }

    @Test
    void updateLanAddress_updatesAndReturnsNoContent() {
        var request = new VpnPeerRestController.UpdateLanAddressRequest("192.168.3.121");

        var response = controller.updateLanAddress("apalveien5", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(forUpdatingPeerConfigurations).updateLanAddress("apalveien5", "192.168.3.121");
        verify(forPublishingEvents).publish("vpn-peers", "peers-updated", "");
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
    void updateLanAddress_propagatesPeerNotFound_withoutPublishing() {
        doThrow(new PeerNotFoundException("Peer not found: ghost"))
            .when(forUpdatingPeerConfigurations).updateLanAddress("ghost", "192.168.3.121");
        var request = new VpnPeerRestController.UpdateLanAddressRequest("192.168.3.121");

        // The controller no longer maps exceptions; GlobalExceptionHandler renders 404. It must
        // still propagate (not swallow) and must not publish an update event on failure.
        assertThatThrownBy(() -> controller.updateLanAddress("ghost", request))
            .isInstanceOf(PeerNotFoundException.class);
        verify(forPublishingEvents, never()).publish(org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void updateLanCidr_updatesAndReturnsNoContent() {
        var request = new VpnPeerRestController.UpdateLanCidrRequest("192.168.3.0/24");

        var response = controller.updateLanCidr("apalveien5", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(updateLanCidrUseCase).updateLanCidr("apalveien5", "192.168.3.0/24");
        verify(forPublishingEvents).publish("vpn-peers", "peers-updated", "");
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
    void updateLanCidr_propagatesPeerNotFound_withoutPublishing() {
        doThrow(new PeerNotFoundException("Peer not found: ghost"))
            .when(updateLanCidrUseCase).updateLanCidr("ghost", "192.168.3.0/24");
        var request = new VpnPeerRestController.UpdateLanCidrRequest("192.168.3.0/24");

        assertThatThrownBy(() -> controller.updateLanCidr("ghost", request))
            .isInstanceOf(PeerNotFoundException.class);
        verify(forPublishingEvents, never()).publish(org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void updateLanCidr_propagatesConflict_withoutPublishing() {
        // updateLanCidr signals a CIDR-already-owned conflict via ConflictException (-> 409
        // at the handler). The controller must propagate it and not publish on failure.
        doThrow(new ConflictException("LAN CIDR 192.168.3.0/24 already owned by peer nuc02"))
            .when(updateLanCidrUseCase).updateLanCidr("apalveien5", "192.168.3.0/24");
        var request = new VpnPeerRestController.UpdateLanCidrRequest("192.168.3.0/24");

        assertThatThrownBy(() -> controller.updateLanCidr("apalveien5", request))
            .isInstanceOf(ConflictException.class);
        verify(forPublishingEvents, never()).publish(org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void renamePeer_renamesAndReturnsNoContent() {
        var request = new VpnPeerRestController.RenamePeerRequest("workstation");

        var response = controller.renamePeer("laptop", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(renamePeerUseCase).renamePeer("laptop", "workstation");
        verify(forPublishingEvents).publish("vpn-peers", "peers-updated", "");
    }

    @Test
    void renamePeer_propagatesPeerNotFound_withoutPublishing() {
        doThrow(new PeerNotFoundException("Peer not found: ghost"))
            .when(renamePeerUseCase).renamePeer("ghost", "phantom");

        assertThatThrownBy(() -> controller.renamePeer("ghost", new VpnPeerRestController.RenamePeerRequest("phantom")))
            .isInstanceOf(PeerNotFoundException.class);
        verify(forPublishingEvents, never()).publish(org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void renamePeer_propagatesInvalidName() {
        doThrow(new IllegalArgumentException("New peer name is empty after sanitisation"))
            .when(renamePeerUseCase).renamePeer("laptop", "   ");

        assertThatThrownBy(() -> controller.renamePeer("laptop", new VpnPeerRestController.RenamePeerRequest("   ")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createPeer_passesNullPeerTypeStraightThroughToUseCase() {
        // The default ("unspecified peerType becomes UBUNTU_SERVER") is a domain rule that lives on
        // CreatePeerUseCase / VpnService now — the controller must not substitute it.
        var created = new CreatePeerUseCase.CreatedPeerUco(
                "nas", "nas", "10.13.13.5", "pub", "priv", "[Interface]", MachineType.UBUNTU_SERVER);
        when(createPeerUseCase.createPeer("nas", null, null, null, "Home media server"))
                .thenReturn(created);
        var request = new VpnPeerRestController.CreatePeerRequest(
                "nas", null, null, null, "Home media server", null, null);

        var response = controller.createPeer(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(createPeerUseCase).createPeer("nas", null, null, null, "Home media server");
    }

    @Test
    void createPeer_passesExplicitPeerTypeThrough() {
        var created = new CreatePeerUseCase.CreatedPeerUco(
                "nas", "nas", "10.13.13.5", "pub", "priv", "[Interface]", MachineType.UBUNTU_SERVER);
        when(createPeerUseCase.createPeer("nas", MachineType.UBUNTU_SERVER, null, null, "Home media server"))
                .thenReturn(created);
        var request = new VpnPeerRestController.CreatePeerRequest(
                "nas", MachineType.UBUNTU_SERVER, null, null, "Home media server", null, null);

        var response = controller.createPeer(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(createPeerUseCase).createPeer("nas", MachineType.UBUNTU_SERVER, null, null, "Home media server");
    }

    @Test
    void createPeer_resolvesPeerTypeFromIntentWhenPresent() {
        // The intent-first flow sends what a machine is for (SERVER / PERSONAL_DEVICE) plus a
        // Windows flag; the intent -> MachineType decision is the domain's (MachineIntent), and the
        // controller delegates to it before calling the unchanged use case.
        var created = new CreatePeerUseCase.CreatedPeerUco(
                "laptop", "laptop", "10.13.13.9", "pub", "priv", "[Interface]", MachineType.WINDOWS_CLIENT);
        when(createPeerUseCase.createPeer("laptop", MachineType.WINDOWS_CLIENT, null, null, null))
                .thenReturn(created);
        var request = new VpnPeerRestController.CreatePeerRequest(
                "laptop", null, null, null, null,
                net.vaier.domain.MachineIntent.PERSONAL_DEVICE, true);

        var response = controller.createPeer(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(createPeerUseCase).createPeer("laptop", MachineType.WINDOWS_CLIENT, null, null, null);
    }

    @Test
    void createPeer_intentTakesPrecedenceAndTreatsAbsentWindowsFlagAsFalse() {
        var created = new CreatePeerUseCase.CreatedPeerUco(
                "nuc", "nuc", "10.13.13.8", "pub", "priv", "[Interface]", MachineType.UBUNTU_SERVER);
        when(createPeerUseCase.createPeer("nuc", MachineType.UBUNTU_SERVER, null, null, null))
                .thenReturn(created);
        var request = new VpnPeerRestController.CreatePeerRequest(
                "nuc", null, null, null, null,
                net.vaier.domain.MachineIntent.SERVER, null);

        controller.createPeer(request);

        verify(createPeerUseCase).createPeer("nuc", MachineType.UBUNTU_SERVER, null, null, null);
    }

    // --- reissue (#247) ---

    @Test
    void reissuePeer_returnsFreshConfigAndArtefactsAndPublishesUpdate() {
        var reissued = new ReissuePeerConfigUseCase.ReissuedPeerUco(
            "apalveien5", "apalveien5", "10.13.13.6", "pub",
            "# VAIER: {\"peerType\":\"UBUNTU_SERVER\"}\n[Interface]\nPrivateKey = k\n"
                + "Address = 10.13.13.6/32\n[Peer]\nAllowedIPs = 10.13.13.0/24,172.31.16.0/20\n",
            MachineType.UBUNTU_SERVER);
        when(reissuePeerConfigUseCase.reissuePeerConfig("apalveien5")).thenReturn(reissued);
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(generateDockerComposeUseCase.generateWireguardClientDockerCompose(eq("apalveien5"), any(), any()))
            .thenReturn("compose-yaml");
        when(generatePeerSetupScriptUseCase.generateSetupScript(eq("apalveien5"), any(), any()))
            .thenReturn(Optional.of("setup-sh"));
        // A setup-script-bearing reissue also hands out a fresh single-use setup token (Slice 4b).
        when(forVendingSetupTokens.issue("apalveien5"))
            .thenReturn(new SetupToken("apalveien5", "fresh-token", 0L));

        var response = controller.reissuePeer("apalveien5");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = (VpnPeerRestController.CreatePeerResponse) response.getBody();
        assertThat(body.configFile()).contains("172.31.16.0/20");
        assertThat(body.dockerCompose()).isEqualTo("compose-yaml");
        assertThat(body.setupScript()).isEqualTo("setup-sh");
        assertThat(body.setupToken()).isEqualTo("fresh-token");
        assertThat(body.availableArtifacts()).contains("WG_CONFIG", "DOCKER_COMPOSE", "SETUP_SCRIPT");
        verify(forPublishingEvents).publish("vpn-peers", "peers-updated", "");
    }

    // --- tokenized anonymous setup download (Slice 4b) ---

    @Test
    void tokenizedSetup_validTokenFirstView_servesScriptAsPlainText() {
        when(forVendingSetupTokens.consume("apalveien5", "tok")).thenReturn(true);
        when(forTrackingPeerConfigRetrieval.markViewedIfNotAlready("apalveien5")).thenReturn(true);
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(generatePeerSetupScriptUseCase.generateSetupScript(eq("apalveien5"), any(), any()))
            .thenReturn(Optional.of("setup-sh"));

        var response = controller.downloadTokenizedSetupScript("apalveien5", "tok");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("setup-sh");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
    }

    @Test
    void tokenizedSetup_missingToken_401_andNeverConsumesOrGenerates() {
        var response = controller.downloadTokenizedSetupScript("apalveien5", null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(generatePeerSetupScriptUseCase, never()).generateSetupScript(any(), any(), any());
        verify(forTrackingPeerConfigRetrieval, never()).markViewedIfNotAlready(any());
    }

    @Test
    void tokenizedSetup_invalidToken_401_andNeverGeneratesScript() {
        when(forVendingSetupTokens.consume("apalveien5", "bad")).thenReturn(false);

        var response = controller.downloadTokenizedSetupScript("apalveien5", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(generatePeerSetupScriptUseCase, never()).generateSetupScript(any(), any(), any());
        verify(forTrackingPeerConfigRetrieval, never()).markViewedIfNotAlready(any());
    }

    @Test
    void tokenizedSetup_validTokenButAlreadyViewed_410_afterBurningTheToken() {
        when(forVendingSetupTokens.consume("apalveien5", "tok")).thenReturn(true);
        when(forTrackingPeerConfigRetrieval.markViewedIfNotAlready("apalveien5")).thenReturn(false);

        var response = controller.downloadTokenizedSetupScript("apalveien5", "tok");

        // A used link is spent even when the config budget is already gone: consume happens first.
        assertThat(response.getStatusCode().value()).isEqualTo(410);
        verify(forVendingSetupTokens).consume("apalveien5", "tok");
        verify(generatePeerSetupScriptUseCase, never()).generateSetupScript(any(), any(), any());
    }

    @Test
    void reissuePeer_unknownPeer_propagatesPeerNotFound_andPublishesNothing() {
        when(reissuePeerConfigUseCase.reissuePeerConfig("ghost"))
            .thenThrow(new PeerNotFoundException("Peer not found: ghost"));

        assertThatThrownBy(() -> controller.reissuePeer("ghost"))
            .isInstanceOf(PeerNotFoundException.class);
        verify(forPublishingEvents, never()).publish(any(), any(), any());
    }

    @Test
    void listPeers_exposesConfigOutOfDateFlag() {
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            new VpnPeerView("nas", "nas", "pub", "10.13.13.6/32", "10.13.13.6",
                "", "51820", "0", false, "0", "0",
                MachineType.UBUNTU_SERVER, true, false, false,
                net.vaier.domain.PeerArtifact.forPeerType(MachineType.UBUNTU_SERVER),
                null, null, null, Optional.empty(), true,
                net.vaier.domain.DeviceCategory.SERVER, false, true)
        ));

        assertThat(controller.listPeers().getBody().get(0).configOutOfDate()).isTrue();
    }

    @Test
    void listPeers_mapsUseCaseViewIntoResponseDto() {
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            view("nas", "nas", false, "", MachineType.UBUNTU_SERVER, "Home media server", Optional.empty())
        ));

        var peer = controller.listPeers().getBody().get(0);

        assertThat(peer.id()).isEqualTo("nas");
        assertThat(peer.name()).isEqualTo("nas");
        assertThat(peer.description()).isEqualTo("Home media server");
        assertThat(peer.peerType()).isEqualTo("UBUNTU_SERVER");
        assertThat(peer.latitude()).isNull();
        assertThat(peer.country()).isNull();
    }

    @Test
    void listPeers_passesConnectedFlagFromUseCase() {
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            view("nas", "nas", true, "", MachineType.UBUNTU_SERVER, null, Optional.empty())
        ));

        assertThat(controller.listPeers().getBody().get(0).connected()).isTrue();
    }

    @Test
    void listPeers_unpacksGeoOptionalIntoFlatFields() {
        var geo = Optional.of(new GeoLocation(59.91, 10.74, "Oslo", "Norway"));
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            view("alice", "alice", true, "203.0.113.10", MachineType.MOBILE_CLIENT, null, geo)
        ));

        var peer = controller.listPeers().getBody().get(0);

        assertThat(peer.latitude()).isEqualTo(59.91);
        assertThat(peer.longitude()).isEqualTo(10.74);
        assertThat(peer.city()).isEqualTo("Oslo");
        assertThat(peer.country()).isEqualTo("Norway");
    }

    @Test
    void listPeers_emptyGeoLeavesAllGeoFieldsNull() {
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            view("alice", "alice", true, "203.0.113.10", MachineType.MOBILE_CLIENT, null, Optional.empty())
        ));

        var peer = controller.listPeers().getBody().get(0);

        assertThat(peer.latitude()).isNull();
        assertThat(peer.longitude()).isNull();
        assertThat(peer.city()).isNull();
        assertThat(peer.country()).isNull();
    }

    // --- device category ---

    @Test
    void listPeers_exposesEffectiveDeviceCategoryAndOverrideFlag() {
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            new VpnPeerView("nas", "nas", "pub", "10.13.13.6/32", "10.13.13.6",
                "", "51820", "0", false, "0", "0",
                MachineType.UBUNTU_SERVER, true, false, false,
                net.vaier.domain.PeerArtifact.forPeerType(MachineType.UBUNTU_SERVER),
                null, null, null, Optional.empty(), true,
                net.vaier.domain.DeviceCategory.NAS, true, true)
        ));

        var peer = controller.listPeers().getBody().get(0);

        assertThat(peer.deviceCategory()).isEqualTo("NAS");
        assertThat(peer.deviceCategoryOverridden()).isTrue();
    }

    @Test
    void updateDeviceCategory_updatesAndReturnsNoContent() {
        var request = new VpnPeerRestController.UpdateDeviceCategoryRequest("NAS");

        var response = controller.updateDeviceCategory("nas", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(updatePeerDeviceCategoryUseCase).updatePeerDeviceCategory("nas", "NAS");
        verify(forPublishingEvents).publish("vpn-peers", "peers-updated", "");
    }

    @Test
    void updateDeviceCategory_nullBodyIsTreatedAsClear() {
        var response = controller.updateDeviceCategory("nas", null);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(updatePeerDeviceCategoryUseCase).updatePeerDeviceCategory("nas", null);
    }

    @Test
    void updateDeviceCategory_propagatesInvalidValue_withoutPublishing() {
        doThrow(new IllegalArgumentException("bad category"))
            .when(updatePeerDeviceCategoryUseCase).updatePeerDeviceCategory("nas", "BANANA");
        var request = new VpnPeerRestController.UpdateDeviceCategoryRequest("BANANA");

        assertThatThrownBy(() -> controller.updateDeviceCategory("nas", request))
            .isInstanceOf(IllegalArgumentException.class);
        verify(forPublishingEvents, never()).publish(any(), any(), any());
    }

    @Test
    void updateDescription_updatesAndReturnsNoContent() {
        var request = new VpnPeerRestController.UpdateDescriptionRequest("Home media server");

        var response = controller.updateDescription("nas", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(forUpdatingPeerConfigurations).updateDescription("nas", "Home media server");
        verify(forPublishingEvents).publish("vpn-peers", "peers-updated", "");
    }

    @Test
    void updateDescription_nullBodyIsTreatedAsClear() {
        var response = controller.updateDescription("nas", null);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(forUpdatingPeerConfigurations).updateDescription("nas", null);
    }

    @Test
    void updateDescription_propagatesPeerNotFound_withoutPublishing() {
        doThrow(new PeerNotFoundException("Peer not found: ghost"))
            .when(forUpdatingPeerConfigurations).updateDescription("ghost", "anything");
        var request = new VpnPeerRestController.UpdateDescriptionRequest("anything");

        assertThatThrownBy(() -> controller.updateDescription("ghost", request))
            .isInstanceOf(PeerNotFoundException.class);
        verify(forPublishingEvents, never()).publish(org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString(),
                                                    org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void getServerLocation_returnsLocationWhenResolved() {
        when(getServerLocationUseCase.getServerLocation())
            .thenReturn(Optional.of(new ServerLocation("vaier.example.com", 59.91, 10.74, "Oslo", "Norway", "172.31.0.0/16")));

        var response = controller.getServerLocation();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = response.getBody();
        assertThat(body.publicHost()).isEqualTo("vaier.example.com");
        assertThat(body.latitude()).isEqualTo(59.91);
        assertThat(body.longitude()).isEqualTo(10.74);
        assertThat(body.city()).isEqualTo("Oslo");
        assertThat(body.country()).isEqualTo("Norway");
        assertThat(body.lanCidr()).isEqualTo("172.31.0.0/16");
    }

    @Test
    void getServerLocation_returns404WhenUnavailable() {
        when(getServerLocationUseCase.getServerLocation()).thenReturn(Optional.empty());

        var response = controller.getServerLocation();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
