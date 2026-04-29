package net.vaier.integration.controller;

import net.vaier.application.CreatePeerUseCase.CreatedPeerUco;
import net.vaier.application.GetPeerConfigUseCase.PeerConfigResult;
import net.vaier.domain.MachineType;
import net.vaier.domain.VpnClient;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VpnPeerControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void listPeers_returnsEmptyListOnException() throws Exception {
        when(getVpnClientsUseCase.getClients()).thenThrow(new RuntimeException("WireGuard not available"));

        mockMvc.perform(get("/vpn/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listPeers_returnsMappedPeerList() throws Exception {
        VpnClient client = new VpnClient("pubkey123", "10.13.13.2/32", "1.2.3.4",
                "51820", "2024-01-01", "100", "200");
        when(getVpnClientsUseCase.getClients()).thenReturn(List.of(client));
        when(resolveVpnPeerNameUseCase.resolvePeerNameByIp("10.13.13.2")).thenReturn("peer1");
        when(getPeerConfigUseCase.getPeerConfigByIp("10.13.13.2")).thenReturn(
                Optional.of(new PeerConfigResult("peer1", "10.13.13.2", "[Interface]", MachineType.UBUNTU_SERVER)));

        mockMvc.perform(get("/vpn/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].name").value("peer1"))
               .andExpect(jsonPath("$[0].publicKey").value("pubkey123"))
               .andExpect(jsonPath("$[0].peerType").value("UBUNTU_SERVER"));
    }

    @Test
    void createPeer_returns200WithCreatedPeerInfo() throws Exception {
        CreatedPeerUco created = new CreatedPeerUco(
                "peer1", "10.13.13.2", "pubkey", "privkey", "[Interface]\n...", MachineType.UBUNTU_SERVER);
        when(createPeerUseCase.createPeer(eq("peer1"), eq(MachineType.UBUNTU_SERVER), any()))
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
    void createPeer_defaultsPeerTypeToUbuntuServerWhenNull() throws Exception {
        CreatedPeerUco created = new CreatedPeerUco(
                "peer1", "10.13.13.2", "pubkey", "privkey", "[Interface]", MachineType.UBUNTU_SERVER);
        when(createPeerUseCase.createPeer(eq("peer1"), eq(MachineType.UBUNTU_SERVER), any()))
                .thenReturn(created);

        mockMvc.perform(post("/vpn/peers")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"name":"peer1"}
                           """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.peerType").value("UBUNTU_SERVER"));
    }

    @Test
    void deletePeer_returns204OnSuccess() throws Exception {
        mockMvc.perform(delete("/vpn/peers/peer1"))
               .andExpect(status().isNoContent());

        verify(deletePeerUseCase).deletePeer("peer1");
    }

    @Test
    void deletePeer_returns404WhenNotFound() throws Exception {
        doThrow(new IllegalArgumentException("Peer not found: peer1"))
                .when(deletePeerUseCase).deletePeer(eq("peer1"));

        mockMvc.perform(delete("/vpn/peers/peer1"))
               .andExpect(status().isNotFound());
    }

    @Test
    void deletePeer_returns500OnUnexpectedError() throws Exception {
        doThrow(new RuntimeException("Unexpected error"))
                .when(deletePeerUseCase).deletePeer(eq("peer1"));

        mockMvc.perform(delete("/vpn/peers/peer1"))
               .andExpect(status().isInternalServerError());
    }

    @Test
    void downloadConfigFile_returnsFileAsAttachment() throws Exception {
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
        when(getPeerConfigUseCase.getPeerConfig("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/vpn/peers/unknown/config-file"))
               .andExpect(status().isNotFound());
    }

    @Test
    void getPeerConfig_returns200WithJsonConfig() throws Exception {
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
}
