package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VpnClientTest {

    @Test
    void isConnected_reflectsHandshakeRecency() {
        record Row(String description, String handshake, boolean expected) {}
        String recent = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        String stale = String.valueOf(System.currentTimeMillis() / 1000 - 3600);
        List<Row> rows = List.of(
            new Row("handshake is recent", recent, true),
            new Row("handshake is older than threshold", stale, false),
            new Row("handshake is zero", "0", false),
            new Row("handshake is not a number", "not-a-number", false),
            new Row("handshake is null", null, false)
        );

        for (Row row : rows) {
            VpnClient client = new VpnClient("pk", "10.0.0.2/32", "1.2.3.4", "51820", row.handshake(), "0", "0");
            assertThat(client.isConnected()).as(row.description()).isEqualTo(row.expected());
        }
    }

    @Test
    void latestHandshakeEpoch_parsesOrDefaultsToZero() {
        record Row(String description, String handshake, long expected) {}
        List<Row> rows = List.of(
            new Row("parses numeric handshake", "1700000000", 1700000000L),
            new Row("trims surrounding whitespace", "  1700000000 ", 1700000000L),
            new Row("null defaults to zero", null, 0L),
            new Row("non-numeric defaults to zero", "not-a-number", 0L)
        );

        for (Row row : rows) {
            VpnClient client = new VpnClient("pk", "10.0.0.2/32", "1.2.3.4", "51820", row.handshake(), "0", "0");
            assertThat(client.latestHandshakeEpoch()).as(row.description()).isEqualTo(row.expected());
        }
    }

    @Test
    void vpnIp_extractsTheTunnelAddressFromAllowedIps() {
        record Row(String description, String allowedIps, String expected) {}
        List<Row> rows = List.of(
            new Row("strips mask from /32 form", "10.13.13.2/32", "10.13.13.2"),
            new Row("returns bare ip when no mask", "10.13.13.2", "10.13.13.2"),
            // Relay peer: /32 VPN IP first, then a LAN CIDR — the tunnel IP is always the first entry.
            new Row("returns first entry for relay peer with LAN cidr", "10.13.13.5/32, 192.168.1.0/24", "10.13.13.5"),
            new Row("trims surrounding whitespace", "  10.13.13.2/32 ", "10.13.13.2"),
            new Row("null allowedIps returns null", null, null)
        );

        for (Row row : rows) {
            VpnClient client = new VpnClient("pk", row.allowedIps(), "1.2.3.4", "51820", "0", "0", "0");
            assertThat(client.vpnIp()).as(row.description()).isEqualTo(row.expected());
        }
    }

    @Test
    void containsAddress_matchesAgainstAllowedIpsCidrs() {
        record Row(String description, String allowedIps, String address, boolean expected) {}
        String relayAllowedIps = "10.13.13.5/32, 192.168.1.0/24";
        List<Row> rows = List.of(
            new Row("matches /32 peer ip", "10.13.13.2/32", "10.13.13.2", true),
            new Row("rejects other address for /32 peer ip", "10.13.13.2/32", "10.13.13.3", false),
            // wg sometimes emits a bare IP instead of IP/32
            new Row("matches bare ip without mask", "10.13.13.2", "10.13.13.2", true),
            new Row("rejects other address for bare ip", "10.13.13.2", "10.13.13.3", false),
            // Relay peer: /32 VPN IP plus a LAN CIDR behind it.
            new Row("matches address inside LAN cidr", relayAllowedIps, "192.168.1.100", true),
            new Row("matches LAN cidr network address", relayAllowedIps, "192.168.1.1", true),
            new Row("matches LAN cidr broadcast address", relayAllowedIps, "192.168.1.255", true),
            new Row("matches the relay's own /32 address", relayAllowedIps, "10.13.13.5", true),
            new Row("rejects address outside all cidrs", relayAllowedIps, "10.13.13.99", false),
            new Row("rejects address in an unrelated subnet", relayAllowedIps, "192.168.2.1", false),
            new Row("rejects address in an unrelated network", relayAllowedIps, "172.20.0.1", false),
            new Row("respects non-byte-aligned prefix network address", "10.13.13.0/28", "10.13.13.0", true),
            new Row("respects non-byte-aligned prefix last address", "10.13.13.0/28", "10.13.13.15", true),
            new Row("respects non-byte-aligned prefix boundary", "10.13.13.0/28", "10.13.13.16", false),
            new Row("null allowedIps returns false", null, "10.13.13.2", false),
            new Row("null address returns false", "10.13.13.2/32", null, false),
            new Row("empty address returns false", "10.13.13.2/32", "", false),
            new Row("blank address returns false", "10.13.13.2/32", "   ", false),
            // Callers may pass a container name instead of an IP; must not throw or do DNS lookups.
            new Row("non-ip container name returns false", "10.13.13.2/32", "my-container", false),
            new Row("incomplete ip returns false", "10.13.13.2/32", "192.168.1", false),
            new Row("out-of-range octet returns false", "10.13.13.2/32", "192.168.1.999", false),
            new Row("ignores malformed cidr entries but matches a valid one", "not-a-cidr, 192.168.1.0/24, 10.0.0.0/99", "192.168.1.50", true),
            new Row("ignores malformed cidr entries, rejects unmatched address", "not-a-cidr, 192.168.1.0/24, 10.0.0.0/99", "10.0.0.1", false)
        );

        for (Row row : rows) {
            VpnClient client = new VpnClient("pk", row.allowedIps(), "1.2.3.4", "51820", "0", "0", "0");
            assertThat(client.containsAddress(row.address())).as(row.description()).isEqualTo(row.expected());
        }
    }
}
