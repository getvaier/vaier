package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WireGuardPeerConfigTest {

    @Test
    void generate_mobileClient_routesAllTrafficAndEmbedsPeerType() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.2", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.MOBILE_CLIENT, null, null, "10.13.13.0/24");

        assertThat(config).contains("AllowedIPs = 0.0.0.0/0");
        assertThat(config).contains("\"peerType\":\"MOBILE_CLIENT\"");
        assertThat(config).doesNotContain("lanCidr");
    }

    @Test
    void generate_windowsClient_routesAllTrafficAndEmbedsPeerType() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.2", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.WINDOWS_CLIENT, null, null, "10.13.13.0/24");

        assertThat(config).contains("AllowedIPs = 0.0.0.0/0");
        assertThat(config).contains("\"peerType\":\"WINDOWS_CLIENT\"");
    }

    @Test
    void generate_ubuntuServer_routesOnlyVpnTraffic() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.UBUNTU_SERVER, null, null, "10.13.13.0/24");

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24");
        assertThat(config).contains("\"peerType\":\"UBUNTU_SERVER\"");
        assertThat(config).doesNotContain("lanCidr");
    }

    @Test
    void generate_ubuntuServerWithLanCidr_appendsLanCidrToAllowedIps() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.UBUNTU_SERVER, "192.168.1.0/24", null, "10.13.13.0/24");

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24, 192.168.1.0/24");
        assertThat(config).contains("\"peerType\":\"UBUNTU_SERVER\"");
        assertThat(config).contains("\"lanCidr\":\"192.168.1.0/24\"");
    }

    @Test
    void generate_windowsServer_routesOnlyVpnTraffic() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.4", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.WINDOWS_SERVER, null, null, "10.13.13.0/24");

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24");
        assertThat(config).contains("\"peerType\":\"WINDOWS_SERVER\"");
    }

    @Test
    void generate_ubuntuServerWithLanAddress_embedsLanAddressInMetadata() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.UBUNTU_SERVER, null, "192.168.3.121", "10.13.13.0/24");

        assertThat(config).contains("\"lanAddress\":\"192.168.3.121\"");
        assertThat(config).doesNotContain("\"lanCidr\"");
    }

    @Test
    void generate_mobileClientWithLanAddress_doesNotEmbedLanAddress() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.2", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.MOBILE_CLIENT, null, "192.168.3.121", "10.13.13.0/24");

        assertThat(config).doesNotContain("lanAddress");
    }

    @Test
    void generate_ubuntuServer_usesConfiguredSubnetNotDefault() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.10.10.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", PeerType.UBUNTU_SERVER, null, null, "10.10.10.0/24");

        assertThat(config).contains("AllowedIPs = 10.10.10.0/24");
        assertThat(config).doesNotContain("10.13.13.0/24");
    }

    @Test
    void generate_clientType_includesDnsLine() {
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.2", "serverPk", "psk",
                "vpn.example.com:51820", PeerType.MOBILE_CLIENT, null, null, "10.13.13.0/24");

        assertThat(config).contains("DNS = 172.20.0.53");
    }

    @Test
    void generate_serverType_omitsDnsLine() {
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.3", "serverPk", "psk",
                "vpn.example.com:51820", PeerType.UBUNTU_SERVER, null, null, "10.13.13.0/24");

        assertThat(config).doesNotContain("DNS =");
    }
}
