package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VpnClientTest {

    @Test
    void isConnected_whenHandshakeIsRecent_returnsTrue() {
        String recent = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        VpnClient client = new VpnClient("pk", "10.0.0.2/32", "1.2.3.4", "51820", recent, "0", "0");

        assertThat(client.isConnected()).isTrue();
    }

    @Test
    void isConnected_whenHandshakeIsOlderThanThreshold_returnsFalse() {
        String stale = String.valueOf(System.currentTimeMillis() / 1000 - 3600);
        VpnClient client = new VpnClient("pk", "10.0.0.2/32", "1.2.3.4", "51820", stale, "0", "0");

        assertThat(client.isConnected()).isFalse();
    }

    @Test
    void isConnected_whenHandshakeIsZero_returnsFalse() {
        VpnClient client = new VpnClient("pk", "10.0.0.2/32", "1.2.3.4", "51820", "0", "0", "0");

        assertThat(client.isConnected()).isFalse();
    }

    @Test
    void isConnected_whenHandshakeIsNotANumber_returnsFalse() {
        VpnClient client = new VpnClient("pk", "10.0.0.2/32", "1.2.3.4", "51820", "not-a-number", "0", "0");

        assertThat(client.isConnected()).isFalse();
    }

    @Test
    void isConnected_whenHandshakeIsNull_returnsFalse() {
        VpnClient client = new VpnClient("pk", "10.0.0.2/32", "1.2.3.4", "51820", null, "0", "0");

        assertThat(client.isConnected()).isFalse();
    }

    @Test
    void containsAddress_matchesSlash32PeerIp() {
        VpnClient client = new VpnClient("pk", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0");

        assertThat(client.containsAddress("10.13.13.2")).isTrue();
        assertThat(client.containsAddress("10.13.13.3")).isFalse();
    }

    @Test
    void containsAddress_matchesBareIpWithoutMask() {
        // wg sometimes emits a bare IP instead of IP/32
        VpnClient client = new VpnClient("pk", "10.13.13.2", "1.2.3.4", "51820", "0", "0", "0");

        assertThat(client.containsAddress("10.13.13.2")).isTrue();
        assertThat(client.containsAddress("10.13.13.3")).isFalse();
    }

    @Test
    void containsAddress_matchesAddressInsideLanCidr() {
        // Relay peer: /32 VPN IP plus a LAN CIDR behind it.
        VpnClient client = new VpnClient("pk", "10.13.13.5/32, 192.168.1.0/24", "1.2.3.4", "51820", "0", "0", "0");

        assertThat(client.containsAddress("192.168.1.100")).isTrue();
        assertThat(client.containsAddress("192.168.1.1")).isTrue();
        assertThat(client.containsAddress("192.168.1.255")).isTrue();
        assertThat(client.containsAddress("10.13.13.5")).isTrue();
    }

    @Test
    void containsAddress_rejectsAddressOutsideAllCidrs() {
        VpnClient client = new VpnClient("pk", "10.13.13.5/32, 192.168.1.0/24", "1.2.3.4", "51820", "0", "0", "0");

        assertThat(client.containsAddress("10.13.13.99")).isFalse();
        assertThat(client.containsAddress("192.168.2.1")).isFalse();
        assertThat(client.containsAddress("172.20.0.1")).isFalse();
    }

    @Test
    void containsAddress_respectsNonByteAlignedPrefix() {
        VpnClient client = new VpnClient("pk", "10.13.13.0/28", "1.2.3.4", "51820", "0", "0", "0");

        assertThat(client.containsAddress("10.13.13.0")).isTrue();
        assertThat(client.containsAddress("10.13.13.15")).isTrue();
        assertThat(client.containsAddress("10.13.13.16")).isFalse();
    }

    @Test
    void containsAddress_whenAllowedIpsIsNull_returnsFalse() {
        VpnClient client = new VpnClient("pk", null, "1.2.3.4", "51820", "0", "0", "0");

        assertThat(client.containsAddress("10.13.13.2")).isFalse();
    }

    @Test
    void containsAddress_whenAddressIsNullOrBlank_returnsFalse() {
        VpnClient client = new VpnClient("pk", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0");

        assertThat(client.containsAddress(null)).isFalse();
        assertThat(client.containsAddress("")).isFalse();
        assertThat(client.containsAddress("   ")).isFalse();
    }

    @Test
    void containsAddress_whenAddressIsNotAnIp_returnsFalse() {
        // Callers may pass a container name instead of an IP; must not throw or do DNS lookups.
        VpnClient client = new VpnClient("pk", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0");

        assertThat(client.containsAddress("my-container")).isFalse();
        assertThat(client.containsAddress("192.168.1")).isFalse();
        assertThat(client.containsAddress("192.168.1.999")).isFalse();
    }

    @Test
    void containsAddress_ignoresMalformedCidrs() {
        VpnClient client = new VpnClient("pk", "not-a-cidr, 192.168.1.0/24, 10.0.0.0/99", "1.2.3.4", "51820", "0", "0", "0");

        assertThat(client.containsAddress("192.168.1.50")).isTrue();
        assertThat(client.containsAddress("10.0.0.1")).isFalse();
    }
}
