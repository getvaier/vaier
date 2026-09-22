package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    // --- serverLanCidr in client-side AllowedIPs for server peers (#204) ---
    // The server LAN CIDR is the subnet the Vaier server itself sits on. Appending it to a
    // server peer's client-side AllowedIPs lets the peer *initiate* connections into the server's
    // LAN through the tunnel (full-tunnel mobile/Windows clients already can since their
    // AllowedIPs is 0.0.0.0/0). Safe: it's the server's subnet, not the peer's own LAN, so it
    // doesn't hijack the peer's local connectivity the way a relay's own lanCidr would.

    @Test
    void generate_ubuntuServerWithServerLanCidr_appendsToClientAllowedIps() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                null, null, "172.31.0.0/16");

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24,172.31.0.0/16");
    }

    @Test
    void generate_windowsServerWithServerLanCidr_appendsToClientAllowedIps() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.4", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.WINDOWS_SERVER, null, null, "10.13.13.0/24",
                null, null, "172.31.0.0/16");

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24,172.31.0.0/16");
    }

    @Test
    void generate_mobileClientWithServerLanCidr_doesNotChangeAllowedIps() {
        // Mobile/Windows clients already route everything (0.0.0.0/0) — appending the server LAN
        // CIDR would be redundant and could confuse wg-quick's route installation.
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.2", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24",
                null, null, "172.31.0.0/16");

        assertThat(config).contains("AllowedIPs = 0.0.0.0/0");
        assertThat(config).doesNotContain("172.31.0.0/16");
    }

    @Test
    void generate_ubuntuServerWithNullServerLanCidr_unchanged() {
        // Explicit null serverLanCidr behaves the same as the existing no-server-LAN overload.
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                null, null, null);

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24");
        assertThat(config).doesNotContain(",");
    }

    @Test
    void generate_ubuntuServerWithBlankServerLanCidr_unchanged() {
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                null, null, "  ");

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24");
        assertThat(config).doesNotContain(",");
    }

    @Test
    void generate_ubuntuServerWithBothLanCidrAndServerLanCidr_onlyServerLanCidrInAllowedIps() {
        // Relay lanCidr stays out of client-side AllowedIPs (regression from earlier change);
        // server LAN CIDR appends. Both still recorded — lanCidr in VAIER metadata.
        String config = WireGuardPeerConfig.generate(
                "privateKey", "10.13.13.3", "serverPubKey", "presharedKey",
                "vpn.example.com:51820", MachineType.UBUNTU_SERVER, "192.168.1.0/24", null,
                "10.13.13.0/24", null, null, "172.31.0.0/16");

        assertThat(config).contains("AllowedIPs = 10.13.13.0/24,172.31.0.0/16");
        assertThat(config).doesNotContain("192.168.1.0/24,172.31.0.0/16");
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
    void readDirective_findsTheKeyWhetherOrNotEqualsIsSpaced() {
        record Row(String description, String config, String expected) {}
        List<Row> rows = List.of(
            new Row("finds key with spaces around equals",
                "[Interface]\nPrivateKey = abc123\nAddress = 10.13.13.2/32\n", "abc123"),
            new Row("finds key with no spaces around equals",
                "[Interface]\nPrivateKey=abc123\nAddress=10.13.13.2/32\n", "abc123")
        );

        for (Row row : rows) {
            assertThat(WireGuardPeerConfig.readDirective(row.config(), "PrivateKey")).as(row.description()).isEqualTo(row.expected());
        }
    }

    @Test
    void readDirective_returnsEmptyStringWhenTheKeyIsNotThere() {
        record Row(String description, String config, String key) {}
        List<Row> rows = List.of(
            new Row("missing key", "[Interface]\nAddress = 10.13.13.2/32\n", "PrivateKey"),
            new Row("does not match a partial key name", "PresharedKey = xyz789\n", "Key")
        );

        for (Row row : rows) {
            assertThat(WireGuardPeerConfig.readDirective(row.config(), row.key())).as(row.description()).isEmpty();
        }
    }

    @Test
    void readIpAddress_stripsMaskOrReadsABareAddress() {
        record Row(String description, String config, String expected) {}
        List<Row> rows = List.of(
            new Row("strips mask from the Address directive",
                "[Interface]\nPrivateKey = abc\nAddress = 10.13.13.7/32\n", "10.13.13.7"),
            new Row("reads a bare address without a mask",
                "[Interface]\nAddress = 10.13.13.7\n", "10.13.13.7")
        );

        for (Row row : rows) {
            assertThat(WireGuardPeerConfig.readIpAddress(row.config())).as(row.description()).isEqualTo(row.expected());
        }
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

    // --- reissue: re-render from current logic, preserving keys (#247) ---

    @Test
    void reissue_preservesPrivateKeyPresharedKeyAndAddressFromExistingConfig() {
        // A server peer created before the server-LAN-CIDR change: its client AllowedIPs is just
        // the VPN subnet. Reissue must keep the exact keypair, PSK and tunnel IP.
        String existing = WireGuardPeerConfig.generate(
                "PRIV_KEY_ABC", "10.13.13.6", "SERVER_PUB", "PSK_XYZ",
                "vaier.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                null, "apalveien5", null);

        String reissued = WireGuardPeerConfig.reissue(
                existing, MachineType.UBUNTU_SERVER, null, null, null, "apalveien5",
                "SERVER_PUB", "vaier.example.com:51820", "10.13.13.0/24", "172.31.16.0/20");

        assertThat(WireGuardPeerConfig.readDirective(reissued, "PrivateKey")).isEqualTo("PRIV_KEY_ABC");
        assertThat(WireGuardPeerConfig.readDirective(reissued, "PresharedKey")).isEqualTo("PSK_XYZ");
        assertThat(WireGuardPeerConfig.readIpAddress(reissued)).isEqualTo("10.13.13.6");
    }

    @Test
    void reissue_serverPeer_appendsCurrentServerLanCidrToClientAllowedIps() {
        // The #247 scenario: an existing server peer whose AllowedIPs predates server LAN routing.
        String existing = WireGuardPeerConfig.generate(
                "PRIV", "10.13.13.6", "SERVER_PUB", "PSK",
                "vaier.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                null, "apalveien5", null);
        assertThat(existing).contains("AllowedIPs = 10.13.13.0/24");

        String reissued = WireGuardPeerConfig.reissue(
                existing, MachineType.UBUNTU_SERVER, null, null, null, "apalveien5",
                "SERVER_PUB", "vaier.example.com:51820", "10.13.13.0/24", "172.31.16.0/20");

        assertThat(reissued).contains("AllowedIPs = 10.13.13.0/24,172.31.16.0/20");
    }

    @Test
    void reissue_clientPeer_keepsFullTunnelAllowedIps() {
        String existing = WireGuardPeerConfig.generate(
                "PRIV", "10.13.13.2", "SERVER_PUB", "PSK",
                "vaier.example.com:51820", MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24",
                null, "phone", null);

        String reissued = WireGuardPeerConfig.reissue(
                existing, MachineType.MOBILE_CLIENT, null, null, null, "phone",
                "SERVER_PUB", "vaier.example.com:51820", "10.13.13.0/24", "172.31.16.0/20");

        assertThat(reissued).contains("AllowedIPs = 0.0.0.0/0");
        assertThat(reissued).doesNotContain("172.31.16.0/20");
    }

    // --- isOutOfDate: on-disk differs from current rendered config (#247) ---

    @Test
    void isOutOfDate_trueWhenExistingLacksCurrentServerLanCidr() {
        String existing = WireGuardPeerConfig.generate(
                "PRIV", "10.13.13.6", "SERVER_PUB", "PSK",
                "vaier.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                null, "apalveien5", null);

        assertThat(WireGuardPeerConfig.isOutOfDate(
                existing, MachineType.UBUNTU_SERVER, null, null, null, "apalveien5",
                "SERVER_PUB", "vaier.example.com:51820", "10.13.13.0/24", "172.31.16.0/20"))
            .isTrue();
    }

    @Test
    void isOutOfDate_falseWhenRenderedConfigMatchesExisting() {
        // Already carries the server LAN CIDR — re-rendering with the same inputs is a no-op.
        String existing = WireGuardPeerConfig.generate(
                "PRIV", "10.13.13.6", "SERVER_PUB", "PSK",
                "vaier.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                null, "apalveien5", "172.31.16.0/20");

        assertThat(WireGuardPeerConfig.isOutOfDate(
                existing, MachineType.UBUNTU_SERVER, null, null, null, "apalveien5",
                "SERVER_PUB", "vaier.example.com:51820", "10.13.13.0/24", "172.31.16.0/20"))
            .isFalse();
    }

    @Test
    void isOutOfDate_falseWhenOnlyVaierMetadataCommentDiffers() {
        // The "# VAIER:" comment is pure Vaier-side metadata — never installed into the tunnel.
        // Out-of-date must reflect divergence in the real tunnel directives only. Simulate the
        // Jackson-written metadata line: a different field order *and* a deviceCategory key that
        // generate() does not emit. All real directives are identical to the rendered config, so
        // the peer is NOT out of date.
        String rendered = WireGuardPeerConfig.generate(
                "PRIV", "10.13.13.6", "SERVER_PUB", "PSK",
                "vaier.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                null, "apalveien5", "172.31.16.0/20");

        String jacksonMetaLine =
                "# VAIER: {\"peerType\":\"UBUNTU_SERVER\",\"name\":\"apalveien5\",\"deviceCategory\":\"GATEWAY\"}";
        String existing = rendered.lines()
                .map(l -> l.startsWith("# VAIER:") ? jacksonMetaLine : l)
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));

        // Sanity: the raw strings really do differ (only on the metadata line).
        assertThat(existing).isNotEqualTo(rendered);

        assertThat(WireGuardPeerConfig.isOutOfDate(
                existing, MachineType.UBUNTU_SERVER, null, null, null, "apalveien5",
                "SERVER_PUB", "vaier.example.com:51820", "10.13.13.0/24", "172.31.16.0/20"))
            .isFalse();
    }

    @Test
    void isOutOfDate_falseWhenMetadataLineUsesCrlfTerminator() {
        // A config written with a CRLF terminator on its "# VAIER:" line must still be recognised
        // as current: stripping the comment has to consume the whole line break (\r\n), not leave a
        // stray \r that diverges from the LF-generated rendered config.
        String rendered = WireGuardPeerConfig.generate(
                "PRIV", "10.13.13.6", "SERVER_PUB", "PSK",
                "vaier.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                null, "apalveien5", "172.31.16.0/20");

        // Replace only the line break after the metadata comment with CRLF; all other lines stay LF.
        String existing = rendered.replaceFirst("(?m)^(# VAIER:.*)$\n", "$1\r\n");
        assertThat(existing).isNotEqualTo(rendered);

        assertThat(WireGuardPeerConfig.isOutOfDate(
                existing, MachineType.UBUNTU_SERVER, null, null, null, "apalveien5",
                "SERVER_PUB", "vaier.example.com:51820", "10.13.13.0/24", "172.31.16.0/20"))
            .isFalse();
    }

    // --- reissue preserves the operator's device-category override (Part 2) ---

    @Test
    void reissue_retainsDeviceCategoryOverrideInRegeneratedMetadata() {
        String existing = WireGuardPeerConfig.generate(
                "PRIV", "10.13.13.6", "SERVER_PUB", "PSK",
                "vaier.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                null, "apalveien5", null);

        String reissued = WireGuardPeerConfig.reissue(
                existing, MachineType.UBUNTU_SERVER, null, null, null, "apalveien5",
                "SERVER_PUB", "vaier.example.com:51820", "10.13.13.0/24", "172.31.16.0/20", "NAS");

        assertThat(reissued).contains("\"deviceCategory\":\"NAS\"");
    }

    @Test
    void reissue_withoutDeviceCategoryOverride_omitsDeviceCategoryKey() {
        String existing = WireGuardPeerConfig.generate(
                "PRIV", "10.13.13.6", "SERVER_PUB", "PSK",
                "vaier.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
                null, "apalveien5", null);

        String reissued = WireGuardPeerConfig.reissue(
                existing, MachineType.UBUNTU_SERVER, null, null, null, "apalveien5",
                "SERVER_PUB", "vaier.example.com:51820", "10.13.13.0/24", "172.31.16.0/20", null);

        assertThat(reissued).doesNotContain("deviceCategory");
    }

    @Test
    void vaierJson_appendsDeviceCategoryLastWhenOverridePresent() {
        String json = WireGuardPeerConfig.vaierJson(
                MachineType.UBUNTU_SERVER, null, null, "a desc", "apalveien5", "GATEWAY");

        assertThat(json).endsWith(",\"deviceCategory\":\"GATEWAY\"}");
    }

    @Test
    void vaierJson_blankDeviceCategory_omitsKey() {
        String json = WireGuardPeerConfig.vaierJson(
                MachineType.UBUNTU_SERVER, null, null, null, "apalveien5", "  ");

        assertThat(json).doesNotContain("deviceCategory");
    }

    // --- the peer's identity travels in its metadata (§6.22) ---------------------------------------

    @Test
    void generate_writesTheMachineIdIntoTheVaierMetadata() {
        // Without this a freshly created peer has no id on disk, and the config adapter — correctly —
        // refuses to load a peer whose identity is missing rather than inventing one. The peer is added
        // to the WireGuard server and then is invisible to Vaier: no machine, no credential, no backup.
        MachineId identity = MachineId.generate();

        String config = WireGuardPeerConfig.generate("privkey", "10.13.13.9", "srvpub", "psk",
            "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
            null, "NUC 02", null, null, identity);

        assertThat(config).contains("\"id\":\"" + identity.value() + "\"");
    }

    @Test
    void reissue_carriesTheExistingIdentityThrough() {
        // A Reissue re-renders the whole config from current logic. Minting a new identity here would be
        // just as bad as dropping one: the peer's credential, host-key pin and backup job all hang off
        // the id it had a moment ago. Identity is read, never minted — including here.
        MachineId identity = MachineId.generate();
        String existing = WireGuardPeerConfig.generate("privkey", "10.13.13.9", "oldpub", "psk",
            "old.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
            null, "NUC 02", null, null, identity);

        String reissued = WireGuardPeerConfig.reissue(existing, MachineType.UBUNTU_SERVER, null, null,
            null, "NUC 02", "newpub", "new.example.com:51820", "10.13.13.0/24", null);

        assertThat(reissued).contains("\"id\":\"" + identity.value() + "\"");
        assertThat(reissued).contains("new.example.com:51820");
    }

    @Test
    void reissue_aConfigWithNoIdentity_staysWithoutOne() {
        // A pre-§6.22 config that was never migrated has no id to carry. Reissue must not quietly supply
        // one — that would hand the peer a brand-new identity and orphan everything keyed to its old
        // records, which is precisely the failure this refactor exists to remove.
        String legacy = """
            # VAIER: {"peerType":"UBUNTU_SERVER","name":"NUC 02"}
            [Interface]
            PrivateKey = privkey
            Address = 10.13.13.9/32

            [Peer]
            PublicKey = oldpub
            PresharedKey = psk
            Endpoint = old.example.com:51820
            AllowedIPs = 10.13.13.0/24
            PersistentKeepalive = 25
            """;

        String reissued = WireGuardPeerConfig.reissue(legacy, MachineType.UBUNTU_SERVER, null, null,
            null, "NUC 02", "newpub", "new.example.com:51820", "10.13.13.0/24", null);

        assertThat(reissued).doesNotContain("\"id\"");
    }

    // --- a device-held key: the private half was minted on the phone and never existed here (#359) ---

    @Test
    void generate_withNoPrivateKey_omitsThePrivateKeyLineEntirely() {
        // An {@code Enrolment}'s config carries everything but the private key. Writing "PrivateKey = "
        // with nothing after it would be both invalid to wg-quick and a lie about what Vaier holds.
        String config = WireGuardPeerConfig.generate(
            null, "10.13.13.7", "serverPubKey", "presharedKey", "vpn.example.com:51820",
            MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24", null, "Geir's phone", null, null,
            MachineId.generate(), "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=");

        assertThat(config).doesNotContain("PrivateKey");
        assertThat(config).contains("[Interface]");
        assertThat(config).contains("Address = 10.13.13.7/32");
        assertThat(config).contains("PresharedKey = presharedKey");
    }

    @Test
    void generate_withBlankPrivateKey_alsoOmitsTheLine() {
        String config = WireGuardPeerConfig.generate(
            "   ", "10.13.13.7", "serverPubKey", "presharedKey", "vpn.example.com:51820",
            MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24", null, "phone", null, null, null,
            "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=");

        assertThat(config).doesNotContain("PrivateKey");
    }

    @Test
    void generate_withAPublicKey_stampsItIntoTheVaierMetadata() {
        String config = WireGuardPeerConfig.generate(
            null, "10.13.13.7", "serverPubKey", "presharedKey", "vpn.example.com:51820",
            MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24", null, "phone", null, null, null,
            "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=");

        assertThat(config).contains("\"publicKey\":\"xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\"");
    }

    @Test
    void generate_withoutAPublicKey_omitsTheKeyAltogether() {
        // Every other peer derives its public key from the private key Vaier holds, so recording one
        // would be a second copy of a fact — and a key present in metadata is what marks a device-held key.
        String config = WireGuardPeerConfig.generate(
            "privkey", "10.13.13.7", "serverPubKey", "presharedKey", "vpn.example.com:51820",
            MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24", null, "phone");

        assertThat(config).doesNotContain("publicKey");
    }

    @Test
    void reissue_anEnrolledPeer_staysPrivateKeyFreeAndKeepsItsPublicKey() {
        // The whole point of an Enrolment survives a Reissue or it was never true: Vaier must not
        // acquire a private key it never had, and must not forget the public one it was given.
        MachineId identity = MachineId.generate();
        String enrolled = WireGuardPeerConfig.generate(
            null, "10.13.13.7", "oldpub", "psk", "old.example.com:51820",
            MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24", null, "Geir's phone", null, null,
            identity, "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=");

        String reissued = WireGuardPeerConfig.reissue(enrolled, MachineType.MOBILE_CLIENT, null, null,
            null, "Geir's phone", "newpub", "new.example.com:51820", "10.13.13.0/24", null);

        assertThat(reissued).doesNotContain("PrivateKey");
        assertThat(reissued).contains("\"publicKey\":\"xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\"");
        assertThat(reissued).contains("\"id\":\"" + identity.value() + "\"");
        assertThat(reissued).contains("Endpoint = new.example.com:51820");
    }

    @Test
    void reissue_anOrdinaryPeer_keepsItsPrivateKeyAndGainsNoPublicKey() {
        String existing = WireGuardPeerConfig.generate("privkey", "10.13.13.9", "oldpub", "psk",
            "old.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
            null, "NUC 02", null, null, MachineId.generate());

        String reissued = WireGuardPeerConfig.reissue(existing, MachineType.UBUNTU_SERVER, null, null,
            null, "NUC 02", "newpub", "new.example.com:51820", "10.13.13.0/24", null);

        assertThat(reissued).contains("PrivateKey = privkey");
        assertThat(reissued).doesNotContain("publicKey");
    }

    @Test
    void isOutOfDate_anEnrolledPeersFreshConfig_isNotOutOfDate() {
        // A config with no PrivateKey line must not read as "differs from what we would render now" —
        // that would put every enrolled phone permanently in the out-of-date state.
        String enrolled = WireGuardPeerConfig.generate(
            null, "10.13.13.7", "serverpub", "psk", "vpn.example.com:51820",
            MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24", null, "phone", null, null,
            MachineId.generate(), "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=");

        assertThat(WireGuardPeerConfig.isOutOfDate(enrolled, MachineType.MOBILE_CLIENT, null, null,
            null, "phone", "serverpub", "vpn.example.com:51820", "10.13.13.0/24", null)).isFalse();
    }

    @Test
    void isOutOfDate_anEnrolledPeerWhoseServerMoved_isStillNotOutOfDate() {
        // The mark exists to say "a Reissue would change this file". A Reissue of a device-held key is
        // refused — the config it would re-render is one nobody can hand to the phone — so the mark would
        // be a warning with no action behind it, which Vaier does not paint.
        String enrolled = WireGuardPeerConfig.generate(
            null, "10.13.13.7", "oldpub", "psk", "old.example.com:51820",
            MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24", null, "Ruten", null, null,
            MachineId.generate(), "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=");

        // Same peer, but the server has since changed its public key and endpoint: for any ordinary peer
        // this is exactly the divergence the mark is for.
        assertThat(WireGuardPeerConfig.isOutOfDate(enrolled, MachineType.MOBILE_CLIENT, null, null,
            null, "Ruten", "newpub", "new.example.com:51820", "10.13.13.0/24", null)).isFalse();
    }

    @Test
    void isOutOfDate_anOrdinaryPeerWhoseServerMoved_isOutOfDate() {
        // The counterpart, so the carve-out above cannot quietly swallow the whole mark.
        String existing = WireGuardPeerConfig.generate("privkey", "10.13.13.9", "oldpub", "psk",
            "old.example.com:51820", MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24",
            null, "phone", null, null, MachineId.generate());

        assertThat(WireGuardPeerConfig.isOutOfDate(existing, MachineType.MOBILE_CLIENT, null, null,
            null, "phone", "newpub", "new.example.com:51820", "10.13.13.0/24", null)).isTrue();
    }

    @Test
    void deviceHeldKey_isTrueForAConfigWithNoPrivateKeyAndAStampedPublicKey() {
        String enrolled = WireGuardPeerConfig.generate(
            null, "10.13.13.7", "serverpub", "psk", "vpn.example.com:51820",
            MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24", null, "Ruten", null, null,
            MachineId.generate(), "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=");

        assertThat(WireGuardPeerConfig.deviceHeldKey(enrolled)).isTrue();
    }

    @Test
    void deviceHeldKey_isFalseForAKeylessConfigThatStampsNoPublicKey() {
        // One rule, one spelling: the stamped public key is what marks a device-held key, exactly as
        // PeerConfiguration.deviceHeldKey() says. A config that is merely missing its PrivateKey line is
        // damaged, not enrolled, and calling it enrolled would tell the operator the phone made its own key.
        String keyless = WireGuardPeerConfig.generate(null, "10.13.13.9", "serverpub", "psk",
            "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
            null, "NUC 02", null, null, MachineId.generate());

        assertThat(WireGuardPeerConfig.deviceHeldKey(keyless)).isFalse();
    }

    @Test
    void deviceHeldKey_isFalseForAConfigVaierMintedTheKeypairFor() {
        String existing = WireGuardPeerConfig.generate("privkey", "10.13.13.9", "serverpub", "psk",
            "vpn.example.com:51820", MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24",
            null, "NUC 02", null, null, MachineId.generate());

        assertThat(WireGuardPeerConfig.deviceHeldKey(existing)).isFalse();
    }
}
