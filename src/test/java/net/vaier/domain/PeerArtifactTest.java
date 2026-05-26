package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeerArtifactTest {

    @Test
    void ubuntuServer_getsConfigComposeAndScript() {
        assertThat(PeerArtifact.forPeerType(MachineType.UBUNTU_SERVER))
            .containsExactlyInAnyOrder(
                PeerArtifact.WG_CONFIG,
                PeerArtifact.DOCKER_COMPOSE,
                PeerArtifact.SETUP_SCRIPT);
    }

    @Test
    void windowsServer_getsConfigAndCompose_butNoSetupScript() {
        // The bootstrap script is bash-only; Windows servers don't get one.
        assertThat(PeerArtifact.forPeerType(MachineType.WINDOWS_SERVER))
            .containsExactlyInAnyOrder(
                PeerArtifact.WG_CONFIG,
                PeerArtifact.DOCKER_COMPOSE);
    }

    @Test
    void mobileClient_getsConfigAndQrCode() {
        assertThat(PeerArtifact.forPeerType(MachineType.MOBILE_CLIENT))
            .containsExactlyInAnyOrder(
                PeerArtifact.WG_CONFIG,
                PeerArtifact.QR_CODE);
    }

    @Test
    void windowsClient_getsOnlyConfig() {
        assertThat(PeerArtifact.forPeerType(MachineType.WINDOWS_CLIENT))
            .containsExactly(PeerArtifact.WG_CONFIG);
    }

    @Test
    void lanServer_getsNoArtifacts() {
        // LAN servers are not VPN peers — they don't have a WireGuard config of their own.
        assertThat(PeerArtifact.forPeerType(MachineType.LAN_SERVER)).isEmpty();
    }

    @Test
    void nullType_returnsEmptySet() {
        assertThat(PeerArtifact.forPeerType(null)).isEmpty();
    }
}
