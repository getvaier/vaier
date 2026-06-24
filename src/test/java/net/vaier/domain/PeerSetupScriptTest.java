package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeerSetupScriptTest {

    private static final String VPN_SUBNET = "10.13.13.0/24";

    private static String serverScript(String lanCidr) {
        return PeerSetupScript.generate("usa-box", "10.13.13.6", "vaier.example.com", "51820",
            "wgconf", lanCidr, VPN_SUBNET, MachineType.UBUNTU_SERVER);
    }

    private static String clientScript() {
        return PeerSetupScript.generate("phone", "10.13.13.2", "vaier.example.com", "51820",
            "wgconf", null, VPN_SUBNET, MachineType.MOBILE_CLIENT);
    }

    // --- internet egress (#174): unconditional on server-type peers ---

    @Test
    void serverPeer_enablesIpForwarding() {
        assertThat(serverScript(null)).contains("net.ipv4.ip_forward=1");
    }

    @Test
    void serverPeer_detectsEgressInterfaceFromDefaultRoute() {
        String script = serverScript(null);
        assertThat(script).contains("ip route show default");
        assertThat(script).contains("EGRESS_IF=");
    }

    @Test
    void serverPeer_masqueradesVpnSubnetOutEgressInterfaceWithoutDestinationFilter() {
        String script = serverScript(null);
        // MASQUERADE all VPN-sourced traffic out the detected egress iface — no -d filter.
        assertThat(script).contains(
            "iptables -t nat -A POSTROUTING -s " + VPN_SUBNET + " -o \"$EGRESS_IF\" -j MASQUERADE");
        // Idempotent: a -C check precedes the -A.
        assertThat(script).contains(
            "iptables -t nat -C POSTROUTING -s " + VPN_SUBNET + " -o \"$EGRESS_IF\" -j MASQUERADE");
        // No destination filter on the egress masquerade rule.
        assertThat(script).doesNotContain(
            "POSTROUTING -s " + VPN_SUBNET + " -d");
    }

    @Test
    void serverPeer_acceptsForwardingFromVpnOutEgressInterface() {
        String script = serverScript(null);
        assertThat(script).contains(
            "iptables -A FORWARD -s " + VPN_SUBNET + " -o \"$EGRESS_IF\" -j ACCEPT");
        assertThat(script).contains("-m state --state RELATED,ESTABLISHED -j ACCEPT");
    }

    @Test
    void serverPeer_persistsEgressRulesViaSystemdUnitThatRedetectsInterfaceAtBoot() {
        String script = serverScript(null);
        assertThat(script).contains("vaier-wg-egress-iptables.service");
        // The unit must re-detect the egress iface at boot, not bake in a possibly-stale name.
        assertThat(script).contains("systemctl enable --now vaier-wg-egress-iptables.service");
    }

    @Test
    void clientPeer_doesNotInstallEgressBlock() {
        String script = clientScript();
        assertThat(script).doesNotContain("EGRESS_IF=");
        assertThat(script).doesNotContain("vaier-wg-egress-iptables.service");
        assertThat(script).doesNotContain("-o \"$EGRESS_IF\" -j MASQUERADE");
    }

    @Test
    void clientPeer_stillForceSplitTunnels() {
        // Egress is gated on server type, but the split-tunnel sed stays for every peer type.
        assertThat(clientScript()).contains("AllowedIPs = " + VPN_SUBNET);
    }

    @Test
    void serverRelayPeer_keepsBothRelayAndEgressRules() {
        String script = serverScript("192.168.3.0/24");
        // Relay rules (destination-filtered to the relay LAN) remain.
        assertThat(script).contains(
            "iptables -t nat -A POSTROUTING -s " + VPN_SUBNET + " -d 192.168.3.0/24 -j MASQUERADE");
        assertThat(script).contains("vaier-wg-relay-iptables.service");
        // Egress rules (no -d filter, out the egress iface) also present.
        assertThat(script).contains(
            "iptables -t nat -A POSTROUTING -s " + VPN_SUBNET + " -o \"$EGRESS_IF\" -j MASQUERADE");
        assertThat(script).contains("vaier-wg-egress-iptables.service");
    }

    @Test
    void serverPeer_enablesIpForwardingExactlyOnceWhenAlsoRelay() {
        // ip_forward persistence should not be duplicated when both relay and egress blocks run.
        String script = serverScript("192.168.3.0/24");
        long persistLines = script.lines()
            .filter(l -> l.contains("'net.ipv4.ip_forward=1' | sudo tee -a /etc/sysctl.d/99-wireguard.conf"))
            .count();
        assertThat(persistLines).isEqualTo(1);
    }
}
