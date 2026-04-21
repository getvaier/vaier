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
    void hasAllowedIpStartingWith_matchesPrefix() {
        VpnClient client = new VpnClient("pk", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0");

        assertThat(client.hasAllowedIpStartingWith("10.13.13.2")).isTrue();
        assertThat(client.hasAllowedIpStartingWith("10.13.13.3")).isFalse();
    }

    @Test
    void hasAllowedIpStartingWith_whenAllowedIpsIsNull_returnsFalse() {
        VpnClient client = new VpnClient("pk", null, "1.2.3.4", "51820", "0", "0", "0");

        assertThat(client.hasAllowedIpStartingWith("10.13.13.2")).isFalse();
    }
}
