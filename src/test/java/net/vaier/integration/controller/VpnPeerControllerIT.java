package net.vaier.integration.controller;

import net.vaier.domain.TestMachineIds;
import net.vaier.application.CreatePeerUseCase.CreatedPeerUco;
import net.vaier.application.GetPeerConfigUseCase.PeerConfigResult;
import net.vaier.application.GetVpnPeersUseCase;
import net.vaier.application.GetVpnPeersUseCase.VpnPeerView;
import net.vaier.application.ReissuePeerConfigUseCase.ReissuedPeerUco;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerNotFoundException;
import net.vaier.domain.PositionTrail;
import net.vaier.domain.ReportedPosition;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VpnPeerControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void listPeers_returnsEmptyListOnException() throws Exception {
        when(getVpnPeersUseCase.getVpnPeers()).thenThrow(new RuntimeException("WireGuard not available"));

        mockMvc.perform(get("/vpn/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listPeers_returnsMappedPeerList() throws Exception {
        VpnPeerView view = VpnPeerView.builder()
                .id("peer1").machineId("11111111-2222-3333-4444-555555555555").name("peer1")
                .publicKey("pubkey123").allowedIps("10.13.13.2/32").tunnelIp("10.13.13.2")
                .endpointIp("1.2.3.4").endpointPort("51820").latestHandshake("2024-01-01")
                .connected(true).transferRx("100").transferTx("200")
                .peerType(MachineType.UBUNTU_SERVER).isServer(true).isClient(false).isRelay(false)
                .availableArtifacts(Set.of()).configOutOfDate(false)
                .deviceCategory(DeviceCategory.SERVER).deviceCategoryOverridden(false).sshAccess(true)
                .build();
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(view));

        mockMvc.perform(get("/vpn/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].name").value("peer1"))
               // The identity the browser joins the fleet on travels over the wire, not just in the view.
               .andExpect(jsonPath("$[0].machineId").value("11111111-2222-3333-4444-555555555555"))
               .andExpect(jsonPath("$[0].publicKey").value("pubkey123"))
               .andExpect(jsonPath("$[0].peerType").value("UBUNTU_SERVER"));
    }

    /** The shape the Explorer reads: a host, an optional label, and an ISO-8601 instant. */
    @Test
    void listPeers_carriesTheLastServiceReachedOverTheWire() throws Exception {
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(VpnPeerView.builder()
                .id("phone").name("phone").peerType(MachineType.MOBILE_CLIENT)
                .availableArtifacts(Set.of()).deviceCategory(DeviceCategory.PHONE)
                .lastServiceReached(Optional.of(new GetVpnPeersUseCase.LastServiceReachedView(
                        "grafana.example.com", "Grafana", Instant.parse("2026-08-11T20:14:00Z"))))
                .build()));

        mockMvc.perform(get("/vpn/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].lastServiceReached.host").value("grafana.example.com"))
               .andExpect(jsonPath("$[0].lastServiceReached.displayName").value("Grafana"))
               .andExpect(jsonPath("$[0].lastServiceReached.at").value("2026-08-11T20:14:00Z"));
    }

    /** A machine that has reached nothing carries null, not an empty object. */
    @Test
    void listPeers_carriesNoLastServiceReachedForAMachineThatHasReachedNothing() throws Exception {
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(VpnPeerView.builder()
                .id("phone").name("phone").peerType(MachineType.MOBILE_CLIENT)
                .availableArtifacts(Set.of()).deviceCategory(DeviceCategory.PHONE)
                .build()));

        mockMvc.perform(get("/vpn/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].lastServiceReached").doesNotExist());
    }

    /**
     * The exact shape the Map's trail layer reads — field names included. The trail is the one part of this
     * payload the browser walks point by point, so a rename here is a silently blank line on the map.
     */
    @Test
    void listPeers_carriesThePositionTrailOverTheWire() throws Exception {
        Instant noon = Instant.parse("2026-08-11T12:00:00Z");
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(VpnPeerView.builder()
                .id("phone").name("phone").peerType(MachineType.MOBILE_CLIENT)
                .availableArtifacts(Set.of()).deviceCategory(DeviceCategory.PHONE)
                .positionTrail(PositionTrail.empty()
                        .extendedWith(ReportedPosition.report(63.4305, 10.3951, 12.0, noon))
                        .extendedWith(ReportedPosition.report(63.5305, 10.4051, null,
                                noon.plus(Duration.ofMinutes(20)))))
                .build()));

        mockMvc.perform(get("/vpn/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].positionTrail.length()").value(2))
               .andExpect(jsonPath("$[0].positionTrail[0].latitude").value(63.4305))
               .andExpect(jsonPath("$[0].positionTrail[0].longitude").value(10.3951))
               .andExpect(jsonPath("$[0].positionTrail[0].accuracyMetres").value(12.0))
               .andExpect(jsonPath("$[0].positionTrail[0].at").value("2026-08-11T12:00:00Z"))
               .andExpect(jsonPath("$[0].positionTrail[1].accuracyMetres").doesNotExist());
    }

    /** A machine that has never reported carries an empty list, never null — the map iterates it unguarded. */
    @Test
    void listPeers_carriesAnEmptyPositionTrailForAMachineThatHasNeverReported() throws Exception {
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(VpnPeerView.builder()
                .id("phone").name("phone").peerType(MachineType.MOBILE_CLIENT)
                .availableArtifacts(Set.of()).deviceCategory(DeviceCategory.PHONE)
                .build()));

        mockMvc.perform(get("/vpn/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].positionTrail").isEmpty());
    }

    @Test
    void createPeer_returns200WithCreatedPeerInfo() throws Exception {
        CreatedPeerUco created = new CreatedPeerUco(
                "peer1", TestMachineIds.of("peer1"), "peer1", "10.13.13.2", "pubkey", "privkey",
                "[Interface]\n...", MachineType.UBUNTU_SERVER);
        when(createPeerUseCase.createPeer(eq("peer1"), eq(MachineType.UBUNTU_SERVER), any(), any(), any()))
                .thenReturn(created);

        mockMvc.perform(post("/vpn/peers")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"name":"peer1","peerType":"UBUNTU_SERVER","lanCidr":null}
                           """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name").value("peer1"))
               .andExpect(jsonPath("$.ipAddress").value("10.13.13.2"))
               .andExpect(jsonPath("$.publicKey").value("pubkey"))
               .andExpect(jsonPath("$.peerType").value("UBUNTU_SERVER"));
    }

    @Test
    void createPeer_forwardsNullPeerType_whenOmitted() throws Exception {
        // The controller passes the request's peerType through verbatim; defaulting a null
        // type to UBUNTU_SERVER is the domain's job (GetVpnPeersUseCase/CreatePeerUseCase),
        // not the controller's. So an omitted peerType reaches the use case as null.
        CreatedPeerUco created = new CreatedPeerUco(
                "peer1", TestMachineIds.of("peer1"), "peer1", "10.13.13.2", "pubkey", "privkey",
                "[Interface]", MachineType.UBUNTU_SERVER);
        when(createPeerUseCase.createPeer(eq("peer1"), isNull(), any(), any(), any()))
                .thenReturn(created);

        mockMvc.perform(post("/vpn/peers")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"name":"peer1"}
                           """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.peerType").value("UBUNTU_SERVER"));

        verify(createPeerUseCase).createPeer(eq("peer1"), isNull(), any(), any(), any());
    }

    @Test
    void deletePeer_returns204OnSuccess() throws Exception {
        mockMvc.perform(delete("/vpn/peers/peer1"))
               .andExpect(status().isNoContent());

        verify(deletePeerUseCase).deletePeer(eq("peer1"), any());
    }

    @Test
    void deletePeer_returns404WhenNotFound() throws Exception {
        doThrow(new PeerNotFoundException("Peer not found: peer1"))
                .when(deletePeerUseCase).deletePeer(eq("peer1"), any());

        mockMvc.perform(delete("/vpn/peers/peer1"))
               .andExpect(status().isNotFound());
    }

    @Test
    void deletePeer_returns500OnUnexpectedError() throws Exception {
        doThrow(new RuntimeException("Unexpected error"))
                .when(deletePeerUseCase).deletePeer(eq("peer1"), any());

        mockMvc.perform(delete("/vpn/peers/peer1"))
               .andExpect(status().isInternalServerError());
    }

    @Test
    void downloadConfigFile_returnsFileAsAttachment() throws Exception {
        when(forTrackingPeerConfigRetrieval.markViewedIfNotAlready("peer1")).thenReturn(true);
        when(getPeerConfigUseCase.getPeerConfig("peer1"))
                .thenReturn(Optional.of(new PeerConfigResult(
                        "peer1", "10.13.13.2", "[Interface]\nAddress = 10.13.13.2/32", MachineType.UBUNTU_SERVER)));

        mockMvc.perform(get("/vpn/peers/peer1/config-file"))
               .andExpect(status().isOk())
               .andExpect(header().string("Content-Disposition", "attachment; filename=peer1.conf"))
               .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
               .andExpect(content().string(containsString("[Interface]")));
    }

    @Test
    void downloadConfigFile_returns404WhenPeerNotFound() throws Exception {
        when(forTrackingPeerConfigRetrieval.markViewedIfNotAlready("unknown"))
            .thenThrow(new IllegalStateException("Peer directory not found: unknown"));

        mockMvc.perform(get("/vpn/peers/unknown/config-file"))
               .andExpect(status().isNotFound());
    }

    @Test
    void downloadConfigFile_returns410WhenAlreadyViewed() throws Exception {
        when(forTrackingPeerConfigRetrieval.markViewedIfNotAlready("peer1")).thenReturn(false);

        mockMvc.perform(get("/vpn/peers/peer1/config-file"))
               .andExpect(status().isGone())
               .andExpect(jsonPath("$.reason").value("already-viewed"))
               .andExpect(jsonPath("$.action").value("delete-and-recreate"));
    }

    @Test
    void getPeerConfig_returns200WithJsonConfig() throws Exception {
        when(forTrackingPeerConfigRetrieval.markViewedIfNotAlready("peer1")).thenReturn(true);
        when(getPeerConfigUseCase.getPeerConfig("peer1"))
                .thenReturn(Optional.of(new PeerConfigResult(
                        "peer1", "10.13.13.2", "[Interface]", MachineType.MOBILE_CLIENT)));

        mockMvc.perform(get("/vpn/peers/peer1/config"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name").value("peer1"))
               .andExpect(jsonPath("$.ipAddress").value("10.13.13.2"))
               .andExpect(jsonPath("$.peerType").value("MOBILE_CLIENT"));
    }

    @Test
    void getPeerConfig_returns404WhenPeerNotFound() throws Exception {
        when(getPeerConfigUseCase.getPeerConfig("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/vpn/peers/unknown/config"))
               .andExpect(status().isNotFound());
    }

    @Test
    void getPeerConfig_returns410WhenAlreadyViewed() throws Exception {
        when(getPeerConfigUseCase.getPeerConfig("peer1"))
                .thenReturn(Optional.of(new PeerConfigResult(
                        "peer1", "10.13.13.2", "[Interface]", MachineType.MOBILE_CLIENT)));
        when(forTrackingPeerConfigRetrieval.markViewedIfNotAlready("peer1")).thenReturn(false);

        mockMvc.perform(get("/vpn/peers/peer1/config"))
               .andExpect(status().isGone())
               .andExpect(jsonPath("$.reason").value("already-viewed"))
               .andExpect(jsonPath("$.action").value("delete-and-recreate"));
    }

    @Test
    void getPeerConfig_byIp_resolvesToPeerIdAndMarksUnderThatKey() throws Exception {
        when(getPeerConfigUseCase.getPeerConfig("10.13.13.2"))
                .thenReturn(Optional.of(new PeerConfigResult(
                        "peer1", "10.13.13.2", "[Interface]", MachineType.MOBILE_CLIENT)));
        when(forTrackingPeerConfigRetrieval.markViewedIfNotAlready("peer1")).thenReturn(true);

        mockMvc.perform(get("/vpn/peers/10.13.13.2/config"))
               .andExpect(status().isOk());

        verify(forTrackingPeerConfigRetrieval).markViewedIfNotAlready("peer1");
    }

    @Test
    void getPeerQrCode_returns410WhenAlreadyViewed() throws Exception {
        when(forTrackingPeerConfigRetrieval.markViewedIfNotAlready("peer1")).thenReturn(false);

        mockMvc.perform(get("/vpn/peers/peer1/qr-code"))
               .andExpect(status().isGone())
               .andExpect(jsonPath("$.reason").value("already-viewed"));
    }

    @Test
    void downloadDockerCompose_returns410WhenAlreadyViewed() throws Exception {
        when(forTrackingPeerConfigRetrieval.markViewedIfNotAlready("peer1")).thenReturn(false);

        mockMvc.perform(get("/vpn/peers/peer1/docker-compose"))
               .andExpect(status().isGone())
               .andExpect(jsonPath("$.reason").value("already-viewed"));
    }

    @Test
    void downloadSetupScript_returns410WhenAlreadyViewed() throws Exception {
        when(forTrackingPeerConfigRetrieval.markViewedIfNotAlready("peer1")).thenReturn(false);

        mockMvc.perform(get("/vpn/peers/peer1/setup-script"))
               .andExpect(status().isGone())
               .andExpect(jsonPath("$.reason").value("already-viewed"));
    }

    @Test
    void createPeer_doesNotBurnTheOneShotBudget_soAFirstGetStillSucceeds() throws Exception {
        CreatedPeerUco created = new CreatedPeerUco(
                "peer1", TestMachineIds.of("peer1"), "peer1", "10.13.13.2", "pubkey", "privkey",
                "[Interface]\n...", MachineType.UBUNTU_SERVER);
        when(createPeerUseCase.createPeer(eq("peer1"), eq(MachineType.UBUNTU_SERVER), any(), any(), any()))
                .thenReturn(created);

        mockMvc.perform(post("/vpn/peers")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"name":"peer1","peerType":"UBUNTU_SERVER"}
                           """))
               .andExpect(status().isOk());

        // The create response IS one delivery of the secret (inline configFile + QR), but the
        // gate is only consulted on the five GET endpoints — so a single follow-up GET (e.g. a
        // raw curl) is still allowed before the budget is burned forever.
        verify(forTrackingPeerConfigRetrieval, never()).markViewedIfNotAlready(any());
    }

    @Test
    void reissuePeer_returns200WithFreshConfigAndReopensTheOneShotGate() throws Exception {
        var reissued = new ReissuedPeerUco(
                "peer1", TestMachineIds.of("peer1"), "peer1", "10.13.13.6", "pubkey",
                "# VAIER: {\"peerType\":\"UBUNTU_SERVER\"}\n[Interface]\nPrivateKey = abc\n"
                    + "Address = 10.13.13.6/32\n[Peer]\nAllowedIPs = 10.13.13.0/24,172.31.16.0/20\n",
                MachineType.UBUNTU_SERVER);
        when(reissuePeerConfigUseCase.reissuePeerConfig("peer1")).thenReturn(reissued);
        // After reissue the gate is reset; the use case owns that. A subsequent GET is allowed once.
        when(forTrackingPeerConfigRetrieval.markViewedIfNotAlready("peer1")).thenReturn(true);
        when(getPeerConfigUseCase.getPeerConfig("peer1")).thenReturn(Optional.of(new PeerConfigResult(
                "peer1", "10.13.13.6", reissued.clientConfigFile(), MachineType.UBUNTU_SERVER)));

        mockMvc.perform(post("/vpn/peers/peer1/reissue"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.configFile").value(containsString("172.31.16.0/20")));

        mockMvc.perform(get("/vpn/peers/peer1/config"))
               .andExpect(status().isOk());
    }

    @Test
    void reissuePeer_returns404WhenPeerNotFound() throws Exception {
        when(reissuePeerConfigUseCase.reissuePeerConfig("ghost"))
                .thenThrow(new PeerNotFoundException("Peer not found: ghost"));

        mockMvc.perform(post("/vpn/peers/ghost/reissue"))
               .andExpect(status().isNotFound());
    }

    @Test
    void createPeer_responseIncludesInlineQrPng() throws Exception {
        CreatedPeerUco created = new CreatedPeerUco(
                "peer1", TestMachineIds.of("peer1"), "peer1", "10.13.13.2", "pubkey", "privkey",
                "[Interface]\nPrivateKey = abc\n", MachineType.MOBILE_CLIENT);
        when(createPeerUseCase.createPeer(eq("peer1"), eq(MachineType.MOBILE_CLIENT), any(), any(), any()))
                .thenReturn(created);

        mockMvc.perform(post("/vpn/peers")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"name":"peer1","peerType":"MOBILE_CLIENT"}
                           """))
               .andExpect(status().isOk())
               // PNG signature in base64 always starts with "iVBORw0KGgo".
               .andExpect(jsonPath("$.qrCodePngBase64").value(org.hamcrest.Matchers.startsWith("iVBORw0KGgo")));
    }
}
