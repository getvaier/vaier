package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The peer bootstrap script. These tests exist because on 2026-07-23 this script was pasted into the
 * wrong terminal and reconfigured a Vaier server: it took the stack down, deleted the server's
 * WireGuard interface, rewrote the Docker daemon config, and routed the host's own subnet into the
 * tunnel. Every assertion here is about the script refusing to do that again.
 */
class PeerSetupScriptTest {

    private static final String CONF = """
        [Interface]
        Address = 10.13.13.2/32
        PrivateKey = redacted

        [Peer]
        PublicKey = redacted
        AllowedIPs = 10.13.13.0/24,172.31.32.0/20
        Endpoint = vaier.vaier.net:51820
        """;

    private static String script() {
        return PeerSetupScript.generate("NUC 02", "10.13.13.2", "vaier.vaier.net", "51820",
            CONF, null, "10.13.13.0/24");
    }

    @Test
    void generate_guardsBeforeTouchingAnythingOnTheHost() {
        String s = script();

        assertThat(s).contains(SetupScriptGuard.MARKER);
        int guard = s.indexOf(SetupScriptGuard.MARKER);
        // Every one of these ran on the Vaier server before anyone could react. The guard has to sit
        // ahead of all of them — a refusal after `docker compose down` is not a refusal.
        assertThat(guard).isLessThan(s.indexOf("docker compose down"));
        assertThat(guard).isLessThan(s.indexOf("ip link delete wg0"));
        assertThat(guard).isLessThan(s.indexOf("get.docker.com"));
        assertThat(guard).isLessThan(s.indexOf("daemon.json"));
        assertThat(guard).isLessThan(s.indexOf("$INSTALL_DIR/.env"));
    }

    @Test
    void generate_installsAnInitScriptThatClearsAStaleWg0BeforeEveryTunnelStart() {
        // Colina 27, 2026-09-21: a Docker daemon restart killed wireguard-client, wg0 outlived it in the
        // host netns, and the restarted container failed with "wg0 already exists" — dead until a reboot.
        String s = script();
        String init = "$INSTALL_DIR/wireguard-client/custom-cont-init.d/10-clear-stale-wg0";

        assertThat(s).contains("INIT_SCRIPT_PATH=\"" + init + "\"");
        assertThat(s).contains("cat > \"$INIT_SCRIPT_PATH\" << 'INIT_SCRIPT'");
        assertThat(s).contains("ip link delete wg0 2>/dev/null || true\nINIT_SCRIPT");
        // linuxserver.io warns about a custom-init script that is not root-owned; newer builds refuse it.
        assertThat(s).contains("sudo chown root:root \"$INIT_SCRIPT_PATH\"");
        assertThat(s).contains("sudo chmod 755 \"$INIT_SCRIPT_PATH\"");
        assertThat(s.indexOf(init)).isLessThan(s.indexOf("\ndocker_compose_up\n"));
        assertThat(s).doesNotContain("so it survives Docker restart");
    }

    @Test
    void generate_namesTheMachineTheScriptIsFor() {
        assertThat(script()).contains("VAIER_MACHINE='NUC 02'");
    }

    @Test
    void generate_refusesWhenTheHostIsInsideACidrTheConfigTunnels() {
        // Taken from the client-side AllowedIPs the script will actually install — including the
        // server LAN CIDR, which is what severed staging's uplink.
        assertThat(script()).contains("for vaier_cidr in '10.13.13.0/24' '172.31.32.0/20'");
    }

    @Test
    void generate_fullTunnelConfig_checksTheSubnetTheSplitTunnelRewriteLeavesBehind() {
        String fullTunnel = "[Peer]\nAllowedIPs = 0.0.0.0/0\nEndpoint = vaier.vaier.net:51820\n";

        String s = PeerSetupScript.generate("NUC 02", "10.13.13.2", "vaier.vaier.net", "51820",
            fullTunnel, null, "10.13.13.0/24");

        assertThat(s).contains("for vaier_cidr in '10.13.13.0/24'");
        assertThat(s).doesNotContain("for vaier_cidr in '0.0.0.0/0'");
    }

    @Test
    void generate_relayClearsDontFragmentOnLanReplies_nowAndOnEveryBoot() {
        // Colina 27, 2026-09-23: OpenSprinkler ignores the MSS and the relay's "need to frag", sending
        // 1468-byte DF replies that wg0 (MTU 1420) drops — every multi-packet answer died in the relay.
        String nft = "nft 'add table ip vaier-relay; flush table ip vaier-relay; "
            + "add chain ip vaier-relay pre { type filter hook prerouting priority -150; }; "
            + "add rule ip vaier-relay pre ct direction reply ip saddr 192.168.1.0/24 ip frag-off set 0'";

        String relay = PeerSetupScript.generate("Colina 27", "10.13.13.3", "vaier.vaier.net", "51820",
            CONF, "192.168.1.0/24", "10.13.13.0/24");
        int unit = relay.indexOf("vaier-wg-relay-iptables.service");

        assertThat(relay.substring(0, unit)).contains("sudo " + nft);
        assertThat(relay.substring(unit, relay.indexOf("UNIT_FILE\n", unit))).contains(nft);
        assertThat(script()).doesNotContain("vaier-relay");
    }

    @Test
    void generate_relayForwardsEveryNetworkItsTunnelCarries_bothWays_nowAndOnEveryBoot() {
        // #250: a relay's tunnel accepts exactly its AllowedIPs, so those are the networks it must
        // forward and masquerade to its LAN — and the ones its LAN hosts may reach through it.
        String conf = CONF.replace("AllowedIPs = 10.13.13.0/24,172.31.32.0/20",
            "AllowedIPs = 10.13.13.0/24,172.31.16.0/20,192.168.3.0/24");
        String relay = PeerSetupScript.generate("Colina 27", "10.13.13.3", "vaier.vaier.net", "51820",
            conf, "192.168.1.0/24", "10.13.13.0/24");
        int unit = relay.indexOf("vaier-wg-relay-iptables.service");
        String now = relay.substring(0, unit);
        String onBoot = relay.substring(unit, relay.indexOf("UNIT_FILE\n", unit));

        assertThat(now).contains("sudo sysctl -w net.ipv4.ip_forward=1");
        assertThat(onBoot).contains("After=network-online.target");
        for (String network : List.of("10.13.13.0/24", "172.31.16.0/20", "192.168.3.0/24")) {
            for (String rule : List.of(
                "-t nat %s POSTROUTING -s " + network + " -d 192.168.1.0/24 -j MASQUERADE",
                "%s FORWARD -s " + network + " -d 192.168.1.0/24 -j ACCEPT",
                "%s FORWARD -s 192.168.1.0/24 -d " + network + " -j ACCEPT")) {
                String check = "iptables " + rule.formatted("-C") + " 2>/dev/null";
                String add = "iptables " + rule.formatted("-A");
                assertThat(now).as(rule).contains("sudo " + check + " \\\n  || sudo " + add + "\n");
                assertThat(onBoot).as(rule).contains("'" + check + " || " + add + "'");
            }
        }
        assertThat(relay).doesNotContain("-s 192.168.1.0/24 -d 192.168.1.0/24");
        assertThat(script()).doesNotContain("ip_forward").doesNotContain("FORWARD").doesNotContain("MASQUERADE")
            .doesNotContain("vaier-wg-relay-iptables");
    }

    @Test
    void generate_stampsTheMachineSoALaterWrongScriptRefuses() {
        String s = script();

        // The guard *reads* the stamp at the top; the write must come after the setup has succeeded,
        // so a run that dies halfway never claims this host as the machine.
        assertThat(s).contains(SetupScriptGuard.STAMP_PATH);
        assertThat(s.lastIndexOf(SetupScriptGuard.STAMP_PATH))
            .isGreaterThan(s.indexOf("docker_compose_up\n"));
    }
}
