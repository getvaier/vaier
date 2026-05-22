package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MachineTest {

    @Test
    void fromPeer_withConnectedClient_carriesRuntimeState() {
        PeerConfiguration peer = new PeerConfiguration("nas", "NAS", "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.2", null);
        VpnClient client = new VpnClient("pk", "10.13.13.2/32", "1.2.3.4", "51820", "1700000000", "10", "20");

        Machine machine = Machine.fromPeer(peer, client);

        assertThat(machine.name()).isEqualTo("NAS");
        assertThat(machine.type()).isEqualTo(MachineType.UBUNTU_SERVER);
        assertThat(machine.publicKey()).isEqualTo("pk");
        assertThat(machine.endpointIp()).isEqualTo("1.2.3.4");
        assertThat(machine.lanCidr()).isEqualTo("192.168.1.0/24");
        assertThat(machine.lanAddress()).isEqualTo("192.168.1.2");
        assertThat(machine.runsDocker()).isTrue();
        assertThat(machine.dockerPort()).isNull();
    }

    @Test
    void fromPeer_withoutClient_leavesRuntimeStateNull() {
        PeerConfiguration peer = new PeerConfiguration("laptop", "Laptop", "10.13.13.4", "",
            MachineType.WINDOWS_CLIENT, null, null, null);

        Machine machine = Machine.fromPeer(peer, null);

        assertThat(machine.name()).isEqualTo("Laptop");
        assertThat(machine.publicKey()).isNull();
        assertThat(machine.endpointIp()).isNull();
        assertThat(machine.latestHandshake()).isNull();
        assertThat(machine.runsDocker()).isFalse();
    }

    @Test
    void fromLanServer_buildsLanServerMachine() {
        LanServer server = new LanServer("vpc-box", "172.31.5.20", true, 2375);

        Machine machine = Machine.fromLanServer(server, "172.31.0.0/16");

        assertThat(machine.name()).isEqualTo("vpc-box");
        assertThat(machine.type()).isEqualTo(MachineType.LAN_SERVER);
        assertThat(machine.publicKey()).isNull();
        assertThat(machine.lanCidr()).isEqualTo("172.31.0.0/16");
        assertThat(machine.lanAddress()).isEqualTo("172.31.5.20");
        assertThat(machine.runsDocker()).isTrue();
        assertThat(machine.dockerPort()).isEqualTo(2375);
    }
}
