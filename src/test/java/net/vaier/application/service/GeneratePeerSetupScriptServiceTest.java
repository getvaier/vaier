package net.vaier.application.service;

import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetPeerConfigUseCase.PeerConfigResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneratePeerSetupScriptServiceTest {

    @Mock
    GetPeerConfigUseCase getPeerConfigUseCase;

    @InjectMocks
    GeneratePeerSetupScriptService service;

    @Test
    void generateSetupScript_peerNotFound_returnsEmpty() {
        when(getPeerConfigUseCase.getPeerConfig("unknown")).thenReturn(Optional.empty());

        assertThat(service.generateSetupScript("unknown", "vpn.example.com", "51820")).isEmpty();
    }

    @Test
    void generateSetupScript_peerFound_returnsNonEmptyScript() {
        when(getPeerConfigUseCase.getPeerConfig("alice")).thenReturn(
            Optional.of(new PeerConfigResult("alice", "10.13.13.2", "[Interface]\nAddress=10.13.13.2/32", net.vaier.domain.PeerType.UBUNTU_SERVER))
        );

        Optional<String> result = service.generateSetupScript("alice", "vpn.example.com", "51820");

        assertThat(result).isPresent();
        assertThat(result.get()).isNotBlank();
    }

    @Test
    void generateSetupScript_scriptStartsWithShebang() {
        when(getPeerConfigUseCase.getPeerConfig("alice")).thenReturn(
            Optional.of(new PeerConfigResult("alice", "10.13.13.2", "wg-config", net.vaier.domain.PeerType.UBUNTU_SERVER))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).startsWith("#!/bin/bash");
    }

    @Test
    void generateSetupScript_scriptContainsPeerName() {
        when(getPeerConfigUseCase.getPeerConfig("alice")).thenReturn(
            Optional.of(new PeerConfigResult("alice", "10.13.13.2", "wg-config", net.vaier.domain.PeerType.UBUNTU_SERVER))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("alice");
    }

    @Test
    void generateSetupScript_scriptContainsVpnIp() {
        when(getPeerConfigUseCase.getPeerConfig("alice")).thenReturn(
            Optional.of(new PeerConfigResult("alice", "10.13.13.2", "wg-config", net.vaier.domain.PeerType.UBUNTU_SERVER))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("10.13.13.2");
    }

    @Test
    void generateSetupScript_scriptContainsServerUrl() {
        when(getPeerConfigUseCase.getPeerConfig("alice")).thenReturn(
            Optional.of(new PeerConfigResult("alice", "10.13.13.2", "wg-config", net.vaier.domain.PeerType.UBUNTU_SERVER))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("vpn.example.com");
    }

    @Test
    void generateSetupScript_scriptContainsServerPort() {
        when(getPeerConfigUseCase.getPeerConfig("alice")).thenReturn(
            Optional.of(new PeerConfigResult("alice", "10.13.13.2", "wg-config", net.vaier.domain.PeerType.UBUNTU_SERVER))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("51820");
    }
}
