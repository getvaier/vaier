package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LanServerSetupScriptTest {

    // --- routedDestinations: the domain decision of which CIDRs to route via the relay ---

    @Test
    void routedDestinations_serverLanCidrPlusVpnSubnetPlusSiblingRelayLans_excludesOwnLan() {
        PeerConfiguration relay = relay("apalveien5", "192.168.3.0/24");
        List<PeerConfiguration> all = List.of(
            relay,
            relay("colina-27", "192.168.1.0/24"),
            client("phone"),
            relay("blankrelay", "   "));

        List<String> cidrs = LanServerSetupScript.routedDestinations(
            relay, all, "172.31.16.0/20", "10.13.13.0/24");

        assertThat(cidrs).containsExactly("172.31.16.0/20", "10.13.13.0/24", "192.168.1.0/24");
        assertThat(cidrs).doesNotContain("192.168.3.0/24"); // the host's own relay LAN
    }

    @Test
    void routedDestinations_dropsBlankServerLanCidrAndDeduplicates() {
        PeerConfiguration relay = relay("apalveien5", "192.168.3.0/24");
        // A sibling that happens to share the VPN subnet string must not double up.
        List<PeerConfiguration> all = List.of(relay, relay("dup", "10.13.13.0/24"));

        List<String> cidrs = LanServerSetupScript.routedDestinations(
            relay, all, "  ", "10.13.13.0/24");

        assertThat(cidrs).containsExactly("10.13.13.0/24");
    }

    // --- generate: adaptive blocks ---

    @Test
    void generate_dockerOnly_emitsDockerBlockNoRouteBlock() {
        String s = LanServerSetupScript.generate(2375, null, List.of());

        assertThat(s).startsWith("#!/usr/bin/env bash");
        assertThat(s).contains("set -euo pipefail");
        // Docker-API exposure, ported from the old lan-docker-setup.sh:
        assertThat(s).contains("command -v docker");
        assertThat(s).contains("get.docker.com");
        assertThat(s).contains("tcp://0.0.0.0:2375");
        assertThat(s).contains("/var/snap/docker/current/config/daemon.json");
        assertThat(s).contains("snap.docker.dockerd");
        assertThat(s).contains("/etc/docker/daemon.json");
        assertThat(s).contains("/etc/systemd/system/docker.service.d");
        assertThat(s).contains("ExecStart=");
        assertThat(s).contains("already configured");
        assertThat(s).contains("docker info");
        assertThat(s).contains("security group");
        // no route block
        assertThat(s).doesNotContain("ip route replace");
        assertThat(s).doesNotContain("vaier-lan-routes.service");
    }

    @Test
    void generate_routesOnly_emitsRouteBlockAndOneshotNoDockerBlock() {
        String s = LanServerSetupScript.generate(
            null, "192.168.3.121", List.of("172.31.16.0/20", "10.13.13.0/24"));

        assertThat(s).startsWith("#!/usr/bin/env bash");
        // immediate routes
        assertThat(s).contains("ip route replace 172.31.16.0/20 via 192.168.3.121");
        assertThat(s).contains("ip route replace 10.13.13.0/24 via 192.168.3.121");
        // persistent systemd oneshot
        assertThat(s).contains("/etc/systemd/system/vaier-lan-routes.service");
        assertThat(s).contains("Type=oneshot");
        assertThat(s).contains("systemctl enable --now vaier-lan-routes.service");
        // no docker block
        assertThat(s).doesNotContain("get.docker.com");
        assertThat(s).doesNotContain("daemon.json");
    }

    @Test
    void generate_dockerAndRoutes_emitsBothBlocks() {
        String s = LanServerSetupScript.generate(
            2375, "192.168.3.121", List.of("172.31.16.0/20"));

        assertThat(s).contains("tcp://0.0.0.0:2375");
        assertThat(s).contains("ip route replace 172.31.16.0/20 via 192.168.3.121");
        assertThat(s).contains("vaier-lan-routes.service");
    }

    // --- forHost: the domain decision of what a registered host's script must do ---

    @Test
    void forHost_dockerOnlyVaierAnchored_dockerBlockNoRoutes() {
        LanServer server = new LanServer("nas", "172.31.20.5", true, 2375, null);

        String s = LanServerSetupScript.forHost(server, List.of(), "172.31.16.0/20", "10.13.13.0/24")
            .orElseThrow();

        assertThat(s).contains("tcp://0.0.0.0:2375");
        assertThat(s).doesNotContain("ip route replace");
    }

    @Test
    void forHost_dockerEnabledWithoutPort_usesDefaultPort() {
        LanServer server = new LanServer("nas", "172.31.20.5", true, null, null);

        String s = LanServerSetupScript.forHost(server, List.of(), "172.31.16.0/20", "10.13.13.0/24")
            .orElseThrow();

        assertThat(s).contains("tcp://0.0.0.0:" + LanServerSetupScript.DEFAULT_DOCKER_PORT);
    }

    @Test
    void forHost_relayAnchoredNoDocker_routesOnly() {
        LanServer server = new LanServer("nuc02", "192.168.3.50", false, null, null);
        List<PeerConfiguration> peers = List.of(
            relayWithAddr("apalveien5", "192.168.3.0/24", "192.168.3.121"),
            relayWithAddr("colina-27", "192.168.1.0/24", "192.168.1.10"));

        String s = LanServerSetupScript.forHost(server, peers, "172.31.16.0/20", "10.13.13.0/24")
            .orElseThrow();

        assertThat(s).contains("ip route replace 172.31.16.0/20 via 192.168.3.121");
        assertThat(s).contains("ip route replace 10.13.13.0/24 via 192.168.3.121");
        assertThat(s).contains("ip route replace 192.168.1.0/24 via 192.168.3.121");
        assertThat(s).doesNotContain("get.docker.com");
    }

    @Test
    void forHost_dockerAndRelayAnchored_bothBlocks() {
        LanServer server = new LanServer("nuc02", "192.168.3.50", true, 2375, null);
        List<PeerConfiguration> peers = List.of(relayWithAddr("apalveien5", "192.168.3.0/24", "192.168.3.121"));

        String s = LanServerSetupScript.forHost(server, peers, "172.31.16.0/20", "10.13.13.0/24")
            .orElseThrow();

        assertThat(s).contains("tcp://0.0.0.0:2375");
        assertThat(s).contains("ip route replace 172.31.16.0/20 via 192.168.3.121");
    }

    @Test
    void forHost_vaierAnchoredNoDocker_empty() {
        LanServer server = new LanServer("nas", "172.31.20.5", false, null, null);

        assertThat(LanServerSetupScript.forHost(server, List.of(), "172.31.16.0/20", "10.13.13.0/24"))
            .isEmpty();
    }

    @Test
    void forHost_relayMissingLanAddress_throws() {
        LanServer server = new LanServer("nuc02", "192.168.3.50", false, null, null);
        List<PeerConfiguration> peers = List.of(relay("apalveien5", "192.168.3.0/24")); // lanAddress null

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> LanServerSetupScript.forHost(server, peers, "172.31.16.0/20", "10.13.13.0/24"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("apalveien5");
    }

    private static PeerConfiguration relay(String id, String lanCidr) {
        return new PeerConfiguration(id, id, "10.13.13.9", "[Interface]",
            MachineType.UBUNTU_SERVER, lanCidr, null, null);
    }

    private static PeerConfiguration relayWithAddr(String id, String lanCidr, String lanAddress) {
        return new PeerConfiguration(id, id, "10.13.13.9", "[Interface]",
            MachineType.UBUNTU_SERVER, lanCidr, lanAddress, null);
    }

    private static PeerConfiguration client(String id) {
        return new PeerConfiguration(id, id, "10.13.13.2", "[Interface]",
            MachineType.MOBILE_CLIENT, null, null, null);
    }
}
