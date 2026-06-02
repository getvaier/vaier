package net.vaier.application.service;

import net.vaier.domain.LanServer;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class MachineServiceTest {

    @Mock ForGettingPeerConfigurations forGettingPeerConfigurations;
    @Mock ForGettingVpnClients forGettingVpnClients;
    @Mock ForGettingLanServers forGettingLanServers;
    @Mock ForResolvingServerLanCidr forResolvingServerLanCidr;

    MachineService service;

    @BeforeEach
    void setUp() {
        service = new MachineService(forGettingPeerConfigurations, forGettingVpnClients, forGettingLanServers,
            forResolvingServerLanCidr);
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        lenient().when(forGettingVpnClients.getClients()).thenReturn(List.of());
        lenient().when(forGettingLanServers.getAll()).thenReturn(List.of());
        lenient().when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());
    }

    @Test
    void getAllMachines_emptyWhenNothingRegistered() {
        assertThat(service.getAllMachines()).isEmpty();
    }

    @Test
    void getAllMachines_combinesWgPeerWithRuntimeState() {
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("alice", "10.13.13.2", "", MachineType.UBUNTU_SERVER, null, null)
        ));
        lenient().when(forGettingVpnClients.getClients()).thenReturn(List.of(
            new VpnClient("pubkey", "10.13.13.2/32", "1.2.3.4", "51820",
                "1700000000", "100", "200")
        ));

        List<Machine> machines = service.getAllMachines();

        assertThat(machines).extracting(Machine::name, Machine::type, Machine::publicKey,
                Machine::endpointIp, Machine::latestHandshake, Machine::transferRx, Machine::transferTx)
            .containsExactly(tuple("alice", MachineType.UBUNTU_SERVER, "pubkey",
                "1.2.3.4", "1700000000", "100", "200"));
        assertThat(machines.get(0).runsDocker()).isTrue();
    }

    @Test
    void getAllMachines_wgClientPeer_runsDockerFalse() {
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("phone", "10.13.13.10", "", MachineType.MOBILE_CLIENT, null, null)
        ));
        lenient().when(forGettingVpnClients.getClients()).thenReturn(List.of(
            new VpnClient("pk-phone", "10.13.13.10/32", null, null, null, null, null)
        ));

        List<Machine> machines = service.getAllMachines();

        assertThat(machines).hasSize(1);
        assertThat(machines.get(0).type()).isEqualTo(MachineType.MOBILE_CLIENT);
        assertThat(machines.get(0).runsDocker()).isFalse();
    }

    @Test
    void getAllMachines_wgPeerWithoutVpnClient_hasNullRuntimeFields() {
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("offline", "10.13.13.99", "", MachineType.UBUNTU_SERVER, null, null)
        ));
        lenient().when(forGettingVpnClients.getClients()).thenReturn(List.of());

        List<Machine> machines = service.getAllMachines();

        assertThat(machines).hasSize(1);
        Machine m = machines.get(0);
        assertThat(m.name()).isEqualTo("offline");
        assertThat(m.type()).isEqualTo(MachineType.UBUNTU_SERVER);
        assertThat(m.publicKey()).isNull();
        assertThat(m.allowedIps()).isNull();
        assertThat(m.endpointIp()).isNull();
        assertThat(m.latestHandshake()).isNull();
        assertThat(m.transferRx()).isNull();
        assertThat(m.transferTx()).isNull();
    }

    @Test
    void getAllMachines_wgPeerWithLanCidr_carriedToMachine() {
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("relay", "10.13.13.5", "", MachineType.UBUNTU_SERVER,
                "192.168.3.0/24", "192.168.3.5")
        ));

        Machine m = service.getAllMachines().get(0);

        assertThat(m.lanCidr()).isEqualTo("192.168.3.0/24");
        assertThat(m.lanAddress()).isEqualTo("192.168.3.5");
    }

    @Test
    void getAllMachines_includesLanServers_lanServerHasNullWgFields() {
        lenient().when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "relay")
        ));

        List<Machine> machines = service.getAllMachines();

        assertThat(machines).hasSize(1);
        Machine m = machines.get(0);
        assertThat(m.name()).isEqualTo("nas");
        assertThat(m.type()).isEqualTo(MachineType.LAN_SERVER);
        assertThat(m.publicKey()).isNull();
        assertThat(m.allowedIps()).isNull();
        assertThat(m.endpointIp()).isNull();
        assertThat(m.lanAddress()).isEqualTo("192.168.3.50");
        assertThat(m.runsDocker()).isTrue();
        assertThat(m.dockerPort()).isEqualTo(2375);
    }

    @Test
    void getAllMachines_lanServerRunsDockerFalse_dockerPortNull() {
        lenient().when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "relay")
        ));

        Machine m = service.getAllMachines().get(0);

        assertThat(m.runsDocker()).isFalse();
        assertThat(m.dockerPort()).isNull();
    }

    @Test
    void getAllMachines_lanServerLanCidrResolvedFromContainingRelay() {
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("relay", "10.13.13.5", "", MachineType.UBUNTU_SERVER,
                "192.168.3.0/24", "192.168.3.5")
        ));
        lenient().when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "relay")
        ));

        // both peer "relay" and LAN_SERVER "nas"
        List<Machine> machines = service.getAllMachines();
        Machine nas = machines.stream()
            .filter(m -> m.type() == MachineType.LAN_SERVER)
            .findFirst()
            .orElseThrow();

        assertThat(nas.lanCidr()).isEqualTo("192.168.3.0/24");
    }

    @Test
    void getAllMachines_lanServerAnchoredAtVaierServer_lanCidrIsServerLanCidr() {
        lenient().when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));
        lenient().when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("vpc-box", "172.31.5.20", true, 2375), "Vaier server")
        ));

        Machine m = service.getAllMachines().stream()
            .filter(x -> x.type() == MachineType.LAN_SERVER)
            .findFirst().orElseThrow();

        assertThat(m.lanCidr()).isEqualTo("172.31.0.0/16");
        assertThat(m.lanAddress()).isEqualTo("172.31.5.20");
    }

    @Test
    void getAllMachines_returnsBothWgPeerAndLanServer() {
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("alice", "10.13.13.2", "", MachineType.UBUNTU_SERVER, null, null)
        ));
        lenient().when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), null)
        ));

        List<Machine> machines = service.getAllMachines();

        assertThat(machines).extracting(Machine::name, Machine::type)
            .containsExactlyInAnyOrder(
                tuple("alice", MachineType.UBUNTU_SERVER),
                tuple("nas", MachineType.LAN_SERVER));
    }
}
