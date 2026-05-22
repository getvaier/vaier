package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WireGuardPeerConfigTest {

    @Test
    void generate_mobileClient_routesAllTrafficAndEmbedsMachineType() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.2", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24", null, null);

        assertThat(config).contains("AllowedIPs = 0.0.0.0/0");
        assertThat(config).contains("\"peerType\":\"MOBILE_CLIENT\"");
        assertThat(config).doesNotContain("lanCidr");
    }

    @Test
    void generate_windowsClient_routesAllTrafficAndEmbedsMachineType() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.2", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.WINDOWS_CLIENT, null, null, "10.13.13.0/24", null, null);

        assertThat(config).contains("AllowedIPs = 0.0.0.0/0");
        assertThat(config).contains("\"peerType\":\"WINDOWS_CLIENT\"");
    }

    @Test
    void generate_ubuntuServer_routesOnlyVpnTraffic() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24", null, null);

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24");
        assertThat(config).contains("\"peerType\":\"UBUNTU_SERVER\"");
        assertThat(config).doesNotContain("lanCidr");
    }

    @Test
    void generate_ubuntuServerWithLanCidr_keepsLanCidrOutOfClientSideAllowedIps() {
        // Regression: appending lanCidr to the relay's *client-side* AllowedIPs makes wg-quick
        // install a route for that CIDR via wg0, hijacking the relay's own LAN. The lanCidr only
        // belongs in the server-side wg0.conf [Peer] entry (set by VpnService.addPeerToServer)
        // so the VPN server can route LAN-bound traffic to the relay; the relay then forwards it
        // via its own LAN NIC using ip_forward + iptables NAT (issue #170).
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, "192.168.1.0/24", null, "10.13.13.0/24", null, null);

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24");
        assertThat(config).doesNotContain("AllowedIPs = 10.13.13.0/24, 192.168.1.0/24");
        // lanCidr still recorded in metadata so addPeerToServer / install-script forwarding pick it up
        assertThat(config).contains("\"peerType\":\"UBUNTU_SERVER\"");
        assertThat(config).contains("\"lanCidr\":\"192.168.1.0/24\"");
    }

    @Test
    void generate_windowsServer_routesOnlyVpnTraffic() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.4", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.WINDOWS_SERVER, null, null, "10.13.13.0/24", null, null);

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24");
        assertThat(config).contains("\"peerType\":\"WINDOWS_SERVER\"");
    }

    @Test
    void generate_ubuntuServerWithLanAddress_embedsLanAddressInMetadata() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, "192.168.3.121", "10.13.13.0/24", null, null);

        assertThat(config).contains("\"lanAddress\":\"192.168.3.121\"");
        assertThat(config).doesNotContain("\"lanCidr\"");
    }

    @Test
    void generate_mobileClientWithLanAddress_doesNotEmbedLanAddress() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.2", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.MOBILE_CLIENT, null, "192.168.3.121", "10.13.13.0/24", null, null);

        assertThat(config).doesNotContain("lanAddress");
    }

    @Test
    void generate_ubuntuServer_usesConfiguredSubnetNotDefault() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.10.10.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.10.10.0/24", null, null);

        assertThat(config).contains("AllowedIPs = 10.10.10.0/24");
        assertThat(config).doesNotContain("10.13.13.0/24");
    }

    @Test
    void generate_clientType_includesDnsLine() {
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.2", "serverPk", "psk",
                "vpn.example.com:51820", MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24", null, null);

        assertThat(config).contains("DNS = 172.20.0.53");
    }

    @Test
    void generate_serverType_omitsDnsLine() {
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.3", "serverPk", "psk",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24", null, null);

        assertThat(config).doesNotContain("DNS =");
    }

    // --- operator-supplied description (#54) ---

    @Test
    void generate_withDescription_embedsDescriptionInMetadata() {
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.3", "serverPk", "psk",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                "Home media server (NUC)", null);

        assertThat(config).contains("\"description\":\"Home media server (NUC)\"");
    }

    @Test
    void generate_descriptionEmbeddedForClientTypesToo() {
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.2", "serverPk", "psk",
                "vpn.example.com:51820", MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24",
                "Work phone", null);

        assertThat(config).contains("\"description\":\"Work phone\"");
    }

    @Test
    void generate_nullDescription_omitsDescriptionKey() {
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.3", "serverPk", "psk",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24", null, null);

        assertThat(config).doesNotContain("description");
    }

    @Test
    void generate_blankDescription_omitsDescriptionKey() {
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.3", "serverPk", "psk",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24", "   ", null);

        assertThat(config).doesNotContain("description");
    }

    @Test
    void generate_descriptionWithQuotesAndBackslashes_isJsonEscaped() {
        // Description is free operator text — it must be JSON-escaped so the single-line
        // "# VAIER:" comment stays valid JSON and parseable on read-back.
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.3", "serverPk", "psk",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                "NAS \"box\" at C:\\data", null);

        assertThat(config).contains("\\\"box\\\"");
        assertThat(config).contains("C:\\\\data");
    }

    @Test
    void generate_descriptionWithNewline_isEscapedToStayOnOneLine() {
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.3", "serverPk", "psk",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                "line one\nline two", null);

        String vaierLine = config.lines().filter(l -> l.startsWith("# VAIER:")).findFirst().orElseThrow();
        assertThat(vaierLine).contains("line one\\nline two");
        assertThat(vaierLine).doesNotContain("\n");
    }

    // --- operator-supplied display name (#209) ---

    @Test
    void generate_withName_embedsNameInMetadata() {
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.3", "serverPk", "psk",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                null, "Media Server");

        assertThat(config).contains("\"name\":\"Media Server\"");
    }

    @Test
    void generate_nameEmbeddedForClientTypesToo() {
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.2", "serverPk", "psk",
                "vpn.example.com:51820", MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24",
                null, "Geir's phone");

        assertThat(config).contains("\"name\":\"Geir's phone\"");
    }

    @Test
    void generate_nullName_omitsNameKey() {
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.3", "serverPk", "psk",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24", null, null);

        assertThat(config).doesNotContain("\"name\"");
    }

    @Test
    void generate_nameWithQuotesAndBackslashes_isJsonEscaped() {
        // The display name is free operator text — JSON-escaped so the single-line "# VAIER:"
        // comment stays valid JSON and parseable on read-back.
        String config = WireGuardPeerConfig.generate(
                "pk", "10.13.13.3", "serverPk", "psk",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                null, "NAS \"box\" at C:\\data");

        String vaierLine = config.lines().filter(l -> l.startsWith("# VAIER:")).findFirst().orElseThrow();
        assertThat(vaierLine).contains("\\\"box\\\"");
        assertThat(vaierLine).contains("C:\\\\data");
    }

    // --- readDirective / readIpAddress (#215) — inverse of generate() ---

    @Test
    void readDirective_findsKeyWithSpacesAroundEquals() {
        String config = "[Interface]\nPrivateKey = abc123\nAddress = 10.13.13.2/32\n";

        assertThat(WireGuardPeerConfig.readDirective(config, "PrivateKey")).isEqualTo("abc123");
    }

    @Test
    void readDirective_findsKeyWithNoSpacesAroundEquals() {
        String config = "[Interface]\nPrivateKey=abc123\nAddress=10.13.13.2/32\n";

        assertThat(WireGuardPeerConfig.readDirective(config, "PrivateKey")).isEqualTo("abc123");
    }

    @Test
    void readDirective_returnsEmptyStringForMissingKey() {
        String config = "[Interface]\nAddress = 10.13.13.2/32\n";

        assertThat(WireGuardPeerConfig.readDirective(config, "PrivateKey")).isEmpty();
    }

    @Test
    void readDirective_doesNotMatchPartialKeyName() {
        String config = "PresharedKey = xyz789\n";

        assertThat(WireGuardPeerConfig.readDirective(config, "Key")).isEmpty();
    }

    @Test
    void readIpAddress_stripsMaskFromAddressDirective() {
        String config = "[Interface]\nPrivateKey = abc\nAddress = 10.13.13.7/32\n";

        assertThat(WireGuardPeerConfig.readIpAddress(config)).isEqualTo("10.13.13.7");
    }

    @Test
    void readIpAddress_readsBareAddressWithoutMask() {
        String config = "[Interface]\nAddress = 10.13.13.7\n";

        assertThat(WireGuardPeerConfig.readIpAddress(config)).isEqualTo("10.13.13.7");
    }

    @Test
    void readIpAddress_returnsEmptyWhenNoAddressLine() {
        String config = "[Interface]\nPrivateKey = abc\n";

        assertThat(WireGuardPeerConfig.readIpAddress(config)).isEmpty();
    }

    @Test
    void serverAllowedIps_withoutLanCidr_isJustTheSlash32TunnelIp() {
        assertThat(WireGuardPeerConfig.serverAllowedIps("10.13.13.5", null))
            .isEqualTo("10.13.13.5/32");
        assertThat(WireGuardPeerConfig.serverAllowedIps("10.13.13.5", "  "))
            .isEqualTo("10.13.13.5/32");
    }

    @Test
    void serverAllowedIps_withLanCidr_appendsItCommaJoinedWithoutSpaces() {
        assertThat(WireGuardPeerConfig.serverAllowedIps("10.13.13.5", "192.168.1.0/24"))
            .isEqualTo("10.13.13.5/32,192.168.1.0/24");
    }
}
