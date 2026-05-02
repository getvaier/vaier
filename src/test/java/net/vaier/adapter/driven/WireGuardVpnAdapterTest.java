package net.vaier.adapter.driven;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WireGuardVpnAdapterTest {

    @Test
    void extractValue_findsKeyWithSpacesAroundEquals() {
        String config = "[Interface]\nPrivateKey = abc123\nAddress = 10.13.13.2/32\n";
        assertThat(WireGuardVpnAdapter.extractValue(config, "PrivateKey")).isEqualTo("abc123");
    }

    @Test
    void extractValue_findsKeyWithNoSpacesAroundEquals() {
        String config = "[Interface]\nPrivateKey=abc123\nAddress=10.13.13.2/32\n";
        assertThat(WireGuardVpnAdapter.extractValue(config, "PrivateKey")).isEqualTo("abc123");
    }

    @Test
    void extractValue_returnsEmptyStringForMissingKey() {
        String config = "[Interface]\nAddress = 10.13.13.2/32\n";
        assertThat(WireGuardVpnAdapter.extractValue(config, "PrivateKey")).isEmpty();
    }

    @Test
    void extractValue_doesNotMatchPartialKeyName() {
        String config = "PresharedKey = xyz789\n";
        assertThat(WireGuardVpnAdapter.extractValue(config, "Key")).isEmpty();
    }

    @Test
    void computeRouteDelta_addsNewLanCidrAsRouteAdd() {
        WireGuardVpnAdapter.RouteDelta delta = WireGuardVpnAdapter.computeRouteDelta(
                "10.13.13.3/32",
                "10.13.13.3/32, 192.168.1.0/24");

        assertThat(delta.toAdd()).containsExactly("192.168.1.0/24");
        assertThat(delta.toRemove()).isEmpty();
    }

    @Test
    void computeRouteDelta_skipsHostRoutesInVpnSubnet() {
        // /32 host routes are managed by wg-quick up; never touch them with ip route.
        WireGuardVpnAdapter.RouteDelta delta = WireGuardVpnAdapter.computeRouteDelta(
                "",
                "10.13.13.3/32");

        assertThat(delta.toAdd()).isEmpty();
        assertThat(delta.toRemove()).isEmpty();
    }

    @Test
    void computeRouteDelta_removesLanCidrThatLeftAllowedIps() {
        WireGuardVpnAdapter.RouteDelta delta = WireGuardVpnAdapter.computeRouteDelta(
                "10.13.13.3/32, 192.168.1.0/24",
                "10.13.13.3/32");

        assertThat(delta.toAdd()).isEmpty();
        assertThat(delta.toRemove()).containsExactly("192.168.1.0/24");
    }

    @Test
    void computeRouteDelta_doesNotRemoveHostRouteEvenWhenItLeavesAllowedIps() {
        // /32 routes are wg-quick territory. Don't ip route del them either.
        WireGuardVpnAdapter.RouteDelta delta = WireGuardVpnAdapter.computeRouteDelta(
                "10.13.13.3/32, 192.168.1.0/24",
                "192.168.1.0/24");

        assertThat(delta.toAdd()).containsExactly("192.168.1.0/24");
        assertThat(delta.toRemove()).isEmpty();
    }

    @Test
    void computeRouteDelta_handlesGatewayDefaultRoute() {
        // 0.0.0.0/0 (gateway peer per #174) is not /32 — it must get a kernel route.
        WireGuardVpnAdapter.RouteDelta delta = WireGuardVpnAdapter.computeRouteDelta(
                "10.13.13.3/32",
                "10.13.13.3/32, 0.0.0.0/0");

        assertThat(delta.toAdd()).containsExactly("0.0.0.0/0");
        assertThat(delta.toRemove()).isEmpty();
    }

    @Test
    void computeRouteDelta_swapLanCidr_addsNewRemovesOld() {
        WireGuardVpnAdapter.RouteDelta delta = WireGuardVpnAdapter.computeRouteDelta(
                "10.13.13.3/32, 192.168.1.0/24",
                "10.13.13.3/32, 192.168.50.0/24");

        assertThat(delta.toAdd()).containsExactly("192.168.50.0/24");
        assertThat(delta.toRemove()).containsExactly("192.168.1.0/24");
    }

    @Test
    void computeRouteDelta_unchangedLanCidrIsStillAddedSoIpRouteReplaceFixesDrift() {
        // The bug: ip route replace is idempotent and cheap, so re-issue it on every
        // setPeerAllowedIps even when the CIDR is unchanged. That heals drift caused by
        // earlier wg set calls that silently skipped route installation.
        WireGuardVpnAdapter.RouteDelta delta = WireGuardVpnAdapter.computeRouteDelta(
                "10.13.13.3/32, 192.168.1.0/24",
                "10.13.13.3/32, 192.168.1.0/24");

        assertThat(delta.toAdd()).containsExactly("192.168.1.0/24");
        assertThat(delta.toRemove()).isEmpty();
    }

    @Test
    void computeRouteDelta_handlesWhitespaceAroundCommas() {
        WireGuardVpnAdapter.RouteDelta delta = WireGuardVpnAdapter.computeRouteDelta(
                "10.13.13.3/32 ,192.168.1.0/24",
                "10.13.13.3/32,  192.168.1.0/24 , 192.168.50.0/24");

        assertThat(delta.toAdd()).containsExactlyInAnyOrder("192.168.1.0/24", "192.168.50.0/24");
        assertThat(delta.toRemove()).isEmpty();
    }

    @Test
    void computeRouteDelta_handlesEmptyOldAllowedIps() {
        WireGuardVpnAdapter.RouteDelta delta = WireGuardVpnAdapter.computeRouteDelta(
                "",
                "10.13.13.3/32, 192.168.1.0/24");

        assertThat(delta.toAdd()).containsExactly("192.168.1.0/24");
        assertThat(delta.toRemove()).isEmpty();
    }
}
