package net.vaier.domain;

import org.junit.jupiter.api.Test;

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
    void generate_stampsTheMachineSoALaterWrongScriptRefuses() {
        String s = script();

        // The guard *reads* the stamp at the top; the write must come after the setup has succeeded,
        // so a run that dies halfway never claims this host as the machine.
        assertThat(s).contains(SetupScriptGuard.STAMP_PATH);
        assertThat(s.lastIndexOf(SetupScriptGuard.STAMP_PATH))
            .isGreaterThan(s.indexOf("docker_compose_up\n"));
    }
}
