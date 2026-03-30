package net.vaier.application.service;

import net.vaier.domain.PeerType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the config generation logic in VpnService (no Docker required).
 */
class VpnServiceConfigTest {

    @Test
    void generateClientConfig_mobileClient_routesAllTrafficAndEmbedsPeerType() {
        String config = VpnService.generateClientConfig(
                "privateKey", "10.13.13.2", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.MOBILE_CLIENT, null);

        assertThat(config).contains("AllowedIPs = 0.0.0.0/0");
        assertThat(config).contains("\"peerType\":\"MOBILE_CLIENT\"");
        assertThat(config).doesNotContain("lanCidr");
    }

    @Test
    void generateClientConfig_windowsClient_routesAllTrafficAndEmbedsPeerType() {
        String config = VpnService.generateClientConfig(
                "privateKey", "10.13.13.2", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.WINDOWS_CLIENT, null);

        assertThat(config).contains("AllowedIPs = 0.0.0.0/0");
        assertThat(config).contains("\"peerType\":\"WINDOWS_CLIENT\"");
    }

    @Test
    void generateClientConfig_ubuntuServer_routesOnlyVpnTraffic() {
        String config = VpnService.generateClientConfig(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.UBUNTU_SERVER, null);

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24");
        assertThat(config).contains("\"peerType\":\"UBUNTU_SERVER\"");
        assertThat(config).doesNotContain("lanCidr");
    }

    @Test
    void generateClientConfig_ubuntuServerWithLanCidr_appendsLanCidrToAllowedIps() {
        String config = VpnService.generateClientConfig(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.UBUNTU_SERVER, "192.168.1.0/24");

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24, 192.168.1.0/24");
        assertThat(config).contains("\"peerType\":\"UBUNTU_SERVER\"");
        assertThat(config).contains("\"lanCidr\":\"192.168.1.0/24\"");
    }

    @Test
    void generateClientConfig_windowsServer_routesOnlyVpnTraffic() {
        String config = VpnService.generateClientConfig(
                "privateKey", "10.13.13.4", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.WINDOWS_SERVER, null);

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24");
        assertThat(config).contains("\"peerType\":\"WINDOWS_SERVER\"");
    }

    // --- Pi-hole DNS injection ---

    @Test
    void generateClientConfig_fullTunnelPeer_withPiholeDns_setsPiholeIpAsDns() {
        String config = VpnService.generateClientConfig(
                "privateKey", "10.13.13.2", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.MOBILE_CLIENT, null, Optional.of("172.20.0.100"));

        assertThat(config).contains("DNS = 172.20.0.100");
    }

    @Test
    void generateClientConfig_fullTunnelPeer_withoutPiholeDns_usesDefaultDns() {
        String config = VpnService.generateClientConfig(
                "privateKey", "10.13.13.2", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.MOBILE_CLIENT, null, Optional.empty());

        assertThat(config).contains("DNS = 10.13.13.1");
    }

    @Test
    void generateClientConfig_windowsClientWithPiholeDns_setsPiholeIpAsDns() {
        String config = VpnService.generateClientConfig(
                "privateKey", "10.13.13.2", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.WINDOWS_CLIENT, null, Optional.of("172.20.0.100"));

        assertThat(config).contains("DNS = 172.20.0.100");
    }

    @Test
    void generateClientConfig_serverPeer_doesNotOverrideDns() {
        String config = VpnService.generateClientConfig(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.UBUNTU_SERVER, null, Optional.of("172.20.0.100"));

        assertThat(config).doesNotContain("DNS =");
    }

    @Test
    void generateClientConfig_windowsServerPeer_doesNotOverrideDns() {
        String config = VpnService.generateClientConfig(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.WINDOWS_SERVER, null, Optional.of("172.20.0.100"));

        assertThat(config).doesNotContain("DNS =");
    }
}
