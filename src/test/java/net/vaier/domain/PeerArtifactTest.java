package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeerArtifactTest {

    @Test
    void ubuntuServer_getsConfigComposeAndScript() {
        assertThat(PeerArtifact.forPeer(MachineType.UBUNTU_SERVER, false))
            .containsExactlyInAnyOrder(
                PeerArtifact.WG_CONFIG,
                PeerArtifact.DOCKER_COMPOSE,
                PeerArtifact.SETUP_SCRIPT);
    }

    @Test
    void aPersonalDevice_getsNothing_evenWithAKeyVaierMinted() {
        // It joins through the Vaier app, which makes its own key: Vaier hands out no config or QR for it.
        for (MachineType type : new MachineType[] { MachineType.MOBILE_CLIENT, MachineType.WINDOWS_CLIENT }) {
            assertThat(PeerArtifact.forPeer(type, false)).as(type.name()).isEmpty();
        }
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

    // --- a device-held key: nothing to download (#359) ---

    @Test
    void aDeviceHeldKeyOverridesEveryPeerType() {
        for (MachineType type : MachineType.values()) {
            assertThat(PeerArtifact.forPeer(type, true))
                .as("%s with a device-held key", type).isEmpty();
        }
    }
}
