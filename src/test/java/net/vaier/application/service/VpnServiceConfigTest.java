package net.vaier.application.service;

import net.vaier.domain.PeerType;
import org.junit.jupiter.api.Test;

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

}
