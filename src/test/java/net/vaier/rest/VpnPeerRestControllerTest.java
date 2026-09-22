package net.vaier.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.RecordComponent;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.PeerNotFoundException;
import net.vaier.domain.Placement;
import net.vaier.domain.PlacementSource;
import net.vaier.domain.PositionTrail;
import net.vaier.domain.ReportedPosition;
import net.vaier.domain.UnidentifiedDeviceException;
import net.vaier.domain.ConflictException;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSubscribingToEvents;
import net.vaier.config.ConfigResolver;
import net.vaier.application.CreatePeerUseCase;
import net.vaier.application.DeletePeerUseCase;
import net.vaier.application.EnrolDeviceUseCase;
import net.vaier.application.GenerateDockerComposeUseCase;
import net.vaier.application.GeneratePeerSetupScriptUseCase;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetPeerConfigUseCase.PeerConfigResult;
import net.vaier.application.GetServerLocationUseCase;
import net.vaier.application.GetServerLocationUseCase.ServerLocation;
import net.vaier.application.GetVpnPeersUseCase;
import net.vaier.application.GetVpnPeersUseCase.VpnPeerView;
import net.vaier.application.CheckStandingUseCase;
import net.vaier.application.LeaveFleetUseCase;
import net.vaier.application.ClaimDeviceUseCase;
import net.vaier.application.ForgetMyPositionUseCase;
import net.vaier.application.GetMyDeviceUseCase;
import net.vaier.application.ReissuePeerConfigUseCase;
import net.vaier.application.RenamePeerUseCase;
import net.vaier.application.ReportMyPositionUseCase;
import net.vaier.application.UpdateLanCidrUseCase;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerArtifact;
import net.vaier.domain.SetupToken;
import net.vaier.domain.port.ForTrackingPeerConfigRetrieval;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import net.vaier.domain.port.ForVendingSetupTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VpnPeerRestControllerTest {

    @Mock GetVpnPeersUseCase getVpnPeersUseCase;
    @Mock GetPeerConfigUseCase getPeerConfigUseCase;
    @Mock CreatePeerUseCase createPeerUseCase;
    @Mock DeletePeerUseCase deletePeerUseCase;
    @Mock EnrolDeviceUseCase enrolDeviceUseCase;
    @Mock LeaveFleetUseCase leaveFleetUseCase;
    @Mock CheckStandingUseCase checkStandingUseCase;
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
    @Mock ReportMyPositionUseCase reportMyPositionUseCase;
    @Mock ForgetMyPositionUseCase forgetMyPositionUseCase;
    @Mock ClaimDeviceUseCase claimDeviceUseCase;
    @Mock GetMyDeviceUseCase getMyDeviceUseCase;
    @Mock ConfigResolver configResolver;

    @InjectMocks VpnPeerRestController controller;

    /** The identity a peer with a stored config carries, kept per peer id so a test can assert on it. */
    private static final Map<String, MachineId> IDENTITIES = new ConcurrentHashMap<>();

    private static MachineId identityOf(String peerId) {
        return IDENTITIES.computeIfAbsent(peerId, p -> MachineId.generate());
    }

    private static VpnPeerView view(String id, String name, boolean connected,
                                    String endpointIp, MachineType type, String description,
                                    Optional<GeoLocation> geo) {
        return view(id, name, connected, endpointIp, type, description, geo, Optional.empty());
    }

    private static VpnPeerView view(String id, String name, boolean connected,
                                    String endpointIp, MachineType type, String description,
                                    Optional<GeoLocation> geo, Optional<Placement> placement) {
        return viewBuilder(id, name, connected, endpointIp, type, description)
            .geoLocation(geo)
            .placement(placement)
            .build();
    }

    private static VpnPeerView.VpnPeerViewBuilder viewBuilder(String id, String name, boolean connected,
                                                              String endpointIp, MachineType type,
                                                              String description) {
        return VpnPeerView.builder()
            .id(id).machineId(identityOf(id).value()).name(name)
            .publicKey("pub").allowedIps("10.13.13.2/32").tunnelIp("10.13.13.2")
            .endpointIp(endpointIp).endpointPort("51820").latestHandshake("0").connected(connected)
            .transferRx("0").transferTx("0")
            .peerType(type).isServer(type.isServerType())
            .isClient(type.isVpnPeer() && !type.isServerType()).isRelay(false)
            .availableArtifacts(PeerArtifact.forPeerType(type))
            .description(description)
            .deviceCategory(DeviceCategory.detect(name, type, null)).deviceCategoryOverridden(false)
            .sshAccess(type.isServerType());
    }

    private static HttpServletRequest callerAt(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        return request;
    }

    /** A request as Traefik actually delivers it: the proxy's own address, the client named in XFF. */
    private static HttpServletRequest proxiedFrom(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
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
    void updateLanAddress_refusesAHostname_beforeItReachesThePort() {
        // The launchpad's local path is http://<lanAddress>:<port>; it survives HSTS only while the host is an
        // IP literal, so a hostname is refused here rather than written and discovered a max-age later (#342).
        var request = new VpnPeerRestController.UpdateLanAddressRequest("nas.home.example.com");

        assertThatThrownBy(() -> controller.updateLanAddress("apalveien5", request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("IPv4");
        verify(forUpdatingPeerConfigurations, never()).updateLanAddress(any(), any());
        verify(forPublishingEvents, never()).publish(any(), any(), any());
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
        verify(forPublishingEvents, never()).publish(anyString(), anyString(), anyString());
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
        verify(forPublishingEvents, never()).publish(anyString(), anyString(), anyString());
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
        verify(forPublishingEvents, never()).publish(anyString(), anyString(), anyString());
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
        verify(forPublishingEvents, never()).publish(anyString(), anyString(), anyString());
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
                "nas", TestMachineIds.of("nas"), "nas", "10.13.13.5", "pub", "priv", "[Interface]",
                MachineType.UBUNTU_SERVER);
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
                "nas", TestMachineIds.of("nas"), "nas", "10.13.13.5", "pub", "priv", "[Interface]",
                MachineType.UBUNTU_SERVER);
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
                "laptop", TestMachineIds.of("laptop"), "laptop", "10.13.13.9", "pub", "priv",
                "[Interface]", MachineType.WINDOWS_CLIENT);
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
                "nuc", TestMachineIds.of("nuc"), "nuc", "10.13.13.8", "pub", "priv", "[Interface]",
                MachineType.UBUNTU_SERVER);
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
            "apalveien5", TestMachineIds.of("apalveien5"), "apalveien5", "10.13.13.6", "pub",
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
            viewBuilder("nas", "nas", false, "", MachineType.UBUNTU_SERVER, null)
                .configOutOfDate(true).build()
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
        // The identity the browser joins the fleet on. Without it here, the Explorer has nothing but the
        // display name to match /machines against — and a one-character disagreement between the two routes
        // a peer delete at /lan-servers/<name>, which answers 404 in silence and leaves the peer standing.
        assertThat(peer.machineId()).isEqualTo(identityOf("nas").value());
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
            viewBuilder("nas", "nas", false, "", MachineType.UBUNTU_SERVER, null)
                .configOutOfDate(true)
                .deviceCategory(DeviceCategory.NAS).deviceCategoryOverridden(true).build()
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
        verify(forPublishingEvents, never()).publish(anyString(), anyString(), anyString());
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


    // --- placement on the peer listing ---

    @Test
    void listPeers_carriesThePlacementTheDomainDecided() {
        Placement placement = new Placement(63.4305, 10.3951, PlacementSource.REPORTED,
            Instant.parse("2026-08-11T18:00:00Z"), 12.0, false, null);
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            view("alice", "alice", true, "77.16.37.23", MachineType.MOBILE_CLIENT, null,
                Optional.of(new GeoLocation(59.8989, 10.6324, "Oslo", "Norway")), Optional.of(placement))));

        var body = controller.listPeers().getBody().get(0);

        assertThat(body.placement().latitude()).isEqualTo(63.4305);
        assertThat(body.placement().longitude()).isEqualTo(10.3951);
        assertThat(body.placement().source()).isEqualTo("REPORTED");
        assertThat(body.placement().asOf()).isEqualTo(Instant.parse("2026-08-11T18:00:00Z"));
        assertThat(body.placement().accuracyMetres()).isEqualTo(12.0);
        assertThat(body.placement().stale()).isFalse();
        assertThat(body.placement().place()).isNull();
        // The raw ISP estimate is untouched — other things read it.
        assertThat(body.latitude()).isEqualTo(59.8989);
    }

    @Test
    void listPeers_leavesPlacementNullWhenVaierHasNoHonestAnswer() {
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            view("alice", "alice", false, "77.16.37.23", MachineType.MOBILE_CLIENT, null,
                Optional.of(new GeoLocation(59.8989, 10.6324, "Oslo", "Norway")), Optional.empty())));

        assertThat(controller.listPeers().getBody().get(0).placement()).isNull();
    }

    // --- the position trail on the peer listing ---

    @Test
    void listPeers_carriesThePositionTrailTheDomainRetained() {
        Instant noon = Instant.parse("2026-08-11T12:00:00Z");
        PositionTrail trail = PositionTrail.empty()
            .extendedWith(ReportedPosition.report(63.4305, 10.3951, 12.0, noon))
            .extendedWith(ReportedPosition.report(63.5305, 10.4051, null, noon.plusSeconds(1800)));
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            viewBuilder("alice", "alice", true, "77.16.37.23", MachineType.MOBILE_CLIENT, null)
                .positionTrail(trail).build()));

        var body = controller.listPeers().getBody().get(0);

        assertThat(body.positionTrail()).hasSize(2);
        assertThat(body.positionTrail().get(0).latitude()).isEqualTo(63.4305);
        assertThat(body.positionTrail().get(0).accuracyMetres()).isEqualTo(12.0);
        assertThat(body.positionTrail().get(0).at()).isEqualTo(noon);
        assertThat(body.positionTrail().get(1).longitude()).isEqualTo(10.4051);
        assertThat(body.positionTrail().get(1).accuracyMetres()).isNull();
    }

    /** A machine that has never reported carries an empty trail, never a null the browser must guard. */
    @Test
    void listPeers_carriesAnEmptyTrailForAMachineThatHasNeverReported() {
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            view("alice", "alice", true, "77.16.37.23", MachineType.MOBILE_CLIENT, null,
                Optional.empty())));

        assertThat(controller.listPeers().getBody().get(0).positionTrail()).isEmpty();
    }

    // --- last service reached on the peer listing ---

    /** The operator's question, answered on the row that already stands for the phone. */
    @Test
    void listPeers_carriesTheServiceTheMachineLastReached() {
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            viewBuilder("alice", "alice", true, "", MachineType.MOBILE_CLIENT, null)
                .lastServiceReached(Optional.of(new GetVpnPeersUseCase.LastServiceReachedView(
                    "grafana.example.com", "Grafana", Instant.parse("2026-08-11T20:14:00Z"))))
                .build()));

        var reached = controller.listPeers().getBody().get(0).lastServiceReached();

        assertThat(reached.host()).isEqualTo("grafana.example.com");
        assertThat(reached.displayName()).isEqualTo("Grafana");
        assertThat(reached.at()).isEqualTo(Instant.parse("2026-08-11T20:14:00Z"));
    }

    /** Null, not an empty object: the browser asks "is anything known", and nothing is. */
    @Test
    void listPeers_leavesLastServiceReachedNullWhenNothingIsKnown() {
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            view("alice", "alice", true, "", MachineType.MOBILE_CLIENT, null, Optional.empty())));

        assertThat(controller.listPeers().getBody().get(0).lastServiceReached()).isNull();
    }

    /** A host Vaier publishes no route for still has a host to show. */
    @Test
    void listPeers_carriesAnUnnamedServiceByItsHostAlone() {
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            viewBuilder("alice", "alice", true, "", MachineType.MOBILE_CLIENT, null)
                .lastServiceReached(Optional.of(new GetVpnPeersUseCase.LastServiceReachedView(
                    "vaier.example.com", null, Instant.parse("2026-08-11T20:14:00Z"))))
                .build()));

        var reached = controller.listPeers().getBody().get(0).lastServiceReached();

        assertThat(reached.host()).isEqualTo("vaier.example.com");
        assertThat(reached.displayName()).isNull();
    }

    // --- my-position: which device this is comes from the tunnel or the claim, never the body ---

    @Test
    void reportMyPosition_handsTheUseCaseTheCallerIpAndTheClaimCookie() {
        var request = new VpnPeerRestController.ReportMyPositionRequest(63.4305, 10.3951, 12.0);

        var response = controller.reportMyPosition(request, "claim-token", callerAt("10.13.13.6"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(reportMyPositionUseCase)
            .reportMyPosition("10.13.13.6", "claim-token", 63.4305, 10.3951, 12.0);
        verify(forPublishingEvents).publish("vpn-peers", "peers-updated", "");
    }

    @Test
    void reportMyPosition_acceptsAPositionWithNoAccuracyAndNoClaim() {
        var request = new VpnPeerRestController.ReportMyPositionRequest(63.4305, 10.3951, null);

        controller.reportMyPosition(request, null, callerAt("10.13.13.6"));

        verify(reportMyPositionUseCase).reportMyPosition("10.13.13.6", null, 63.4305, 10.3951, null);
    }

    @Test
    void reportMyPosition_propagatesTheRefusalOfAnUnidentifiedDevice_withoutPublishing() {
        doThrow(new UnidentifiedDeviceException("no idea which device this is"))
            .when(reportMyPositionUseCase).reportMyPosition(eq("203.0.113.9"), any(), any(), any(), any());
        var request = new VpnPeerRestController.ReportMyPositionRequest(63.4305, 10.3951, 12.0);

        assertThatThrownBy(() -> controller.reportMyPosition(request, null, callerAt("203.0.113.9")))
            .isInstanceOf(UnidentifiedDeviceException.class);
        verify(forPublishingEvents, never()).publish(anyString(), anyString(), anyString());
    }

    /** No machine id in the body, ever — so an empty body is a refusal, not a guess. */
    @Test
    void reportMyPosition_rejectsAnEmptyBodyRatherThanGuessing() {
        assertThatThrownBy(() -> controller.reportMyPosition(null, null, callerAt("10.13.13.6")))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(reportMyPositionUseCase);
    }

    /**
     * Behind Traefik the socket address is the proxy's, and the device's real tunnel IP arrives in
     * {@code X-Forwarded-For}. This is the branch that decides identity in production, and it must go
     * through {@link net.vaier.domain.CallerIp} — a future edit reading the header directly would pass
     * every other test in this file while making tunnel identity forgeable.
     */
    @Test
    void reportMyPosition_believesTheForwardedTunnelIpWhenTheProxyIsTrusted() {
        ReflectionTestUtils.setField(controller, "trustedProxyCidr", "172.20.0.0/16");
        var request = new VpnPeerRestController.ReportMyPositionRequest(63.4305, 10.3951, 12.0);

        controller.reportMyPosition(request, null, proxiedFrom("172.20.0.5", "10.13.13.6"));

        verify(reportMyPositionUseCase).reportMyPosition("10.13.13.6", null, 63.4305, 10.3951, 12.0);
    }

    /** The same header from anywhere else is a claim the caller wrote about itself, and is ignored. */
    @Test
    void reportMyPosition_ignoresAForwardedTunnelIpFromAnUntrustedAddress() {
        ReflectionTestUtils.setField(controller, "trustedProxyCidr", "172.20.0.0/16");
        var request = new VpnPeerRestController.ReportMyPositionRequest(63.4305, 10.3951, 12.0);

        controller.reportMyPosition(request, null, proxiedFrom("203.0.113.9", "10.13.13.6"));

        verify(reportMyPositionUseCase).reportMyPosition("203.0.113.9", null, 63.4305, 10.3951, 12.0);
    }

    @Test
    void forgetMyPosition_believesTheForwardedTunnelIpWhenTheProxyIsTrusted() {
        ReflectionTestUtils.setField(controller, "trustedProxyCidr", "172.20.0.0/16");

        controller.forgetMyPosition(null, proxiedFrom("172.20.0.5", "10.13.13.6"));

        verify(forgetMyPositionUseCase).forgetMyPosition("10.13.13.6", null);
    }

    /**
     * The security property, pinned as a shape test: the request body carries coordinates and nothing
     * that could name a machine. A field added here would let a device report someone else's position.
     */
    @Test
    void reportMyPositionRequest_carriesNoWayToNameAMachine() {
        assertThat(VpnPeerRestController.ReportMyPositionRequest.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .containsExactlyInAnyOrder("latitude", "longitude", "accuracyMetres");
    }

    @Test
    void forgetMyPosition_forgetsAndClearsTheClaimCookie() {
        var response = controller.forgetMyPosition("claim-token", callerAt("10.13.13.6"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(forgetMyPositionUseCase).forgetMyPosition("10.13.13.6", "claim-token");
        verify(forPublishingEvents).publish("vpn-peers", "peers-updated", "");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
            .contains("vaier_device_claim=").contains("Max-Age=0");
    }

    @Test
    void forgetMyPosition_propagatesTheRefusalOfAnUnidentifiedDevice_withoutPublishing() {
        doThrow(new UnidentifiedDeviceException("no idea which device this is"))
            .when(forgetMyPositionUseCase).forgetMyPosition("203.0.113.9", null);

        assertThatThrownBy(() -> controller.forgetMyPosition(null, callerAt("203.0.113.9")))
            .isInstanceOf(UnidentifiedDeviceException.class);
        verify(forPublishingEvents, never()).publish(anyString(), anyString(), anyString());
    }

    // --- device-claim: here the machine id IS the operator's deliberate assertion ---

    @Test
    void claimDevice_setsTheClaimAsAHardenedCookie() {
        MachineId phone = identityOf("phone");
        when(claimDeviceUseCase.claimDevice(phone.value())).thenReturn("opaque-token");

        var response = controller.claimDevice(phone.value());

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).contains("vaier_device_claim=opaque-token")
            .contains("HttpOnly").contains("Secure").contains("SameSite=Lax").contains("Path=/vpn/peers");
    }

    // --- my-device: which machine THIS browser has claimed, never a fact about the machine ---

    @Test
    void getMyDevice_namesTheMachineThisBrowserClaimed() {
        MachineId phone = identityOf("phone");
        when(getMyDeviceUseCase.myDevice("claim-token")).thenReturn(Optional.of(phone));

        var response = controller.getMyDevice("claim-token");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().machineId()).isEqualTo(phone.value());
    }

    /**
     * A browser holding no claim, one whose claim was revoked, and one whose token a later claim
     * superseded all read the same: null. The use case cannot distinguish them and must not pretend to.
     */
    @Test
    void getMyDevice_isNullForABrowserThatHoldsNoLiveClaim() {
        when(getMyDeviceUseCase.myDevice(null)).thenReturn(Optional.empty());
        when(getMyDeviceUseCase.myDevice("revoked-or-superseded")).thenReturn(Optional.empty());

        assertThat(controller.getMyDevice(null).getBody().machineId()).isNull();
        assertThat(controller.getMyDevice("revoked-or-superseded").getBody().machineId()).isNull();
    }

    /** Off the tunnel is the ordinary case, so this must never consult the caller's address. */
    @Test
    void getMyDevice_needsNoRequestAtAll_soItCannotDependOnTheTunnel() {
        when(getMyDeviceUseCase.myDevice("claim-token")).thenReturn(Optional.of(identityOf("phone")));

        assertThat(controller.getMyDevice("claim-token").getBody().machineId()).isNotNull();
    }

    @Test
    void claimDevice_propagatesAnUnusableMachineId() {
        when(claimDeviceUseCase.claimDevice("phone"))
            .thenThrow(new IllegalArgumentException("Machine id must be a canonical lowercase UUID: phone"));

        assertThatThrownBy(() -> controller.claimDevice("phone"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- POST /vpn/peers/enrol: a phone joins with a key born on the device (#359 slice 1) ---

    private static final String DEVICE_KEY = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=";

    @Test
    void enrolDevice_handsBackTheConfigTheAppNeeds_andNothingElse() {
        var enrolled = new EnrolDeviceUseCase.EnrolledDeviceUco(
            "geirs-phone", TestMachineIds.of("geirs-phone"), "Geir's phone", "10.13.13.7",
            DEVICE_KEY, "# VAIER: {}\n[Interface]\nAddress = 10.13.13.7/32\n",
            MachineType.MOBILE_CLIENT);
        when(enrolDeviceUseCase.enrol("Geir's phone", DEVICE_KEY)).thenReturn(enrolled);

        var response = controller.enrolDevice(
            new VpnPeerRestController.EnrolDeviceRequest("Geir's phone", DEVICE_KEY));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = response.getBody();
        assertThat(body.id()).isEqualTo("geirs-phone");
        assertThat(body.machineId()).isEqualTo(TestMachineIds.of("geirs-phone").value());
        assertThat(body.name()).isEqualTo("Geir's phone");
        assertThat(body.ipAddress()).isEqualTo("10.13.13.7");
        assertThat(body.configFile()).isEqualTo(enrolled.configFile());
    }

    @Test
    void enrolDeviceResponse_carriesNoKeyOfAnyKind() {
        // The response goes back into a browser and on to the app. A private key cannot be in it — Vaier
        // has none — and the public key is something the device already holds, so it says nothing.
        assertThat(VpnPeerRestController.EnrolDeviceResponse.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .containsExactly("id", "machineId", "name", "ipAddress", "configFile");
    }

    @Test
    void enrolDevice_tellsTheFleetSomethingChanged() {
        var enrolled = new EnrolDeviceUseCase.EnrolledDeviceUco(
            "phone", TestMachineIds.of("phone"), "phone", "10.13.13.7", DEVICE_KEY, "[Interface]",
            MachineType.MOBILE_CLIENT);
        when(enrolDeviceUseCase.enrol("phone", DEVICE_KEY)).thenReturn(enrolled);

        controller.enrolDevice(new VpnPeerRestController.EnrolDeviceRequest("phone", DEVICE_KEY));

        verify(forPublishingEvents).publish("vpn-peers", "peers-updated", "");
    }

    @Test
    void enrolDevice_doesNotJudgeTheKeyItself_theDomainDoes() {
        // A thin controller: a bad key is the domain's refusal (-> 400 via GlobalExceptionHandler),
        // never a second copy of the rule here.
        when(enrolDeviceUseCase.enrol("phone", "not-a-key"))
            .thenThrow(new IllegalArgumentException("WireGuard key must be a 32-byte base64 key"));

        assertThatThrownBy(() -> controller.enrolDevice(
                new VpnPeerRestController.EnrolDeviceRequest("phone", "not-a-key")))
            .isInstanceOf(IllegalArgumentException.class);
        verify(forPublishingEvents, never()).publish(anyString(), anyString(), anyString());
    }

    @Test
    void theListedPeer_saysWhetherItHoldsItsOwnKey() {
        // The pane decides from this whether to offer a Reissue or a regeneration at all, so the fact has
        // to reach the browser in its own right — an empty artefact list says something narrower.
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(
            viewBuilder("ruten", "Ruten", true, "203.0.113.10", MachineType.MOBILE_CLIENT, null)
                .availableArtifacts(Set.of()).deviceHeldKey(true).build(),
            viewBuilder("nuc", "NUC 02", true, "203.0.113.11", MachineType.UBUNTU_SERVER, null).build()));

        var peers = controller.listPeers().getBody();

        assertThat(peers).extracting(
                VpnPeerRestController.VpnPeerResponse::id,
                VpnPeerRestController.VpnPeerResponse::deviceHeldKey)
            .containsExactly(tuple("ruten", true), tuple("nuc", false));
    }

    @Test
    void theConfigEndpoint_listsNoArtefactsForAnEnrolledPeer() {
        // In practice this endpoint answers 410 for such a peer, because enrolment spends the one-shot
        // budget. It must still be honest if it is ever reached: the rule is the domain's, at every edge.
        when(getPeerConfigUseCase.getPeerConfig("phone")).thenReturn(Optional.of(new PeerConfigResult(
            "phone", "phone", "10.13.13.7", "[Interface]\n", MachineType.MOBILE_CLIENT,
            null, null, null, true)));
        when(forTrackingPeerConfigRetrieval.markViewedIfNotAlready("phone")).thenReturn(true);

        var body = (VpnPeerRestController.PeerConfigResponse) controller.getPeerConfig("phone").getBody();

        assertThat(body.availableArtifacts()).isEmpty();
    }

    // --- POST /vpn/peers/leave: a phone removes itself from the fleet (#359 slice 1b) ---

    private static final String LEAVE_PSK = "cGKrDp0z0Fs0IiUrPzuTfnJ7CEZzSXpGX0ZlLBFgLGE=";

    @Test
    void leave_removesThePeerAndTellsTheFleet() {
        when(leaveFleetUseCase.leave(DEVICE_KEY, LEAVE_PSK)).thenReturn(true);

        var response = controller.leaveFleet(
            new VpnPeerRestController.LeaveFleetRequest(DEVICE_KEY, LEAVE_PSK));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(forPublishingEvents).publish("vpn-peers", "peers-updated", "");
    }

    @Test
    void leave_whenNothingIsProved_is404_andSaysNoMoreThanThat() {
        // One answer for "no such key" and "wrong preshared key" alike: an anonymous caller must not
        // be able to learn which peers exist by trying keys.
        when(leaveFleetUseCase.leave(DEVICE_KEY, "wrong-preshared-key")).thenReturn(false);

        var response = controller.leaveFleet(
            new VpnPeerRestController.LeaveFleetRequest(DEVICE_KEY, "wrong-preshared-key"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(forPublishingEvents, never()).publish(anyString(), anyString(), anyString());
    }

    @Test
    void leave_doesNotJudgeTheKeysItself_theDomainDoes() {
        when(leaveFleetUseCase.leave(DEVICE_KEY, null))
            .thenThrow(new IllegalArgumentException("Leaving requires both keys"));

        assertThatThrownBy(() -> controller.leaveFleet(
                new VpnPeerRestController.LeaveFleetRequest(DEVICE_KEY, null)))
            .isInstanceOf(IllegalArgumentException.class);
        verify(forPublishingEvents, never()).publish(anyString(), anyString(), anyString());
    }

    // --- POST /vpn/peers/standing: a phone asks whether it is still in (#359 slice 3) ---

    @Test
    void standing_aMemberIs204_andNothingChanges() {
        when(checkStandingUseCase.isMember(DEVICE_KEY, LEAVE_PSK)).thenReturn(true);

        var response = controller.standing(new VpnPeerRestController.StandingRequest(DEVICE_KEY, LEAVE_PSK));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(forPublishingEvents, never()).publish(anyString(), anyString(), anyString());
    }

    @Test
    void standing_whenNothingIsProved_is404_likeLeaving() {
        when(checkStandingUseCase.isMember(DEVICE_KEY, "wrong")).thenReturn(false);

        var response = controller.standing(new VpnPeerRestController.StandingRequest(DEVICE_KEY, "wrong"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
