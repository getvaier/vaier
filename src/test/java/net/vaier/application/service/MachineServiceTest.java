package net.vaier.application.service;

import net.vaier.config.ConfigResolver;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DiskUnreadableException;
import net.vaier.domain.LanServer;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import net.vaier.domain.SshTarget;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForRunningSshCommands;
import net.vaier.domain.port.ForTrackingHostKeys;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.VaierConfig;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MachineServiceTest {

    @Mock ForGettingPeerConfigurations forGettingPeerConfigurations;
    @Mock ForGettingVpnClients forGettingVpnClients;
    @Mock ForGettingLanServers forGettingLanServers;
    @Mock ForResolvingServerLanCidr forResolvingServerLanCidr;
    @Mock ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    @Mock ForPersistingLanServers forPersistingLanServers;
    @Mock ForPersistingAppConfiguration forPersistingAppConfiguration;
    @Mock ForResolvingSshTargets forResolvingSshTargets;
    @Mock ForRunningSshCommands forRunningSshCommands;
    @Mock ForTrackingHostKeys forTrackingHostKeys;
    @Mock ConfigResolver configResolver;

    MachineService service;

    /** The domain (non-Vaier-server) machines from a getAllMachines() result. */
    private static List<Machine> domainMachines(List<Machine> all) {
        return all.stream().filter(m -> !LanAnchor.VAIER_SERVER_NAME.equals(m.name())).toList();
    }

    @BeforeEach
    void setUp() {
        service = new MachineService(forGettingPeerConfigurations, forGettingVpnClients, forGettingLanServers,
            forResolvingServerLanCidr, forUpdatingPeerConfigurations, forPersistingLanServers,
            forPersistingAppConfiguration, forResolvingSshTargets, forRunningSshCommands,
            forTrackingHostKeys, configResolver);
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        lenient().when(forGettingVpnClients.getClients()).thenReturn(List.of());
        lenient().when(forGettingLanServers.getAll()).thenReturn(List.of());
        lenient().when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());
        lenient().when(forPersistingAppConfiguration.load()).thenReturn(Optional.empty());
    }

    @Test
    void getAllMachines_nothingRegistered_stillContainsOnlyTheVaierServer() {
        List<Machine> all = service.getAllMachines();
        assertThat(domainMachines(all)).isEmpty();
        assertThat(all).extracting(Machine::name).contains(LanAnchor.VAIER_SERVER_NAME);
    }

    @Test
    void getAllMachines_includesVaierServer_effectiveSshAccessDefaultsOn() {
        Machine server = service.getAllMachines().stream()
            .filter(m -> LanAnchor.VAIER_SERVER_NAME.equals(m.name()))
            .findFirst().orElseThrow();

        assertThat(server.deviceCategory()).isEqualTo(net.vaier.domain.DeviceCategory.SERVER);
        assertThat(server.effectiveSshAccess()).isTrue();
    }

    @Test
    void getAllMachines_vaierServer_honoursStoredOverride() {
        lenient().when(forPersistingAppConfiguration.load())
            .thenReturn(Optional.of(VaierConfig.builder().vaierServerSshAccess(false).build()));

        Machine server = service.getVaierServerMachine();

        assertThat(server.effectiveSshAccess()).isFalse();
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

        List<Machine> machines = domainMachines(service.getAllMachines());

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

        List<Machine> machines = domainMachines(service.getAllMachines());

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

        List<Machine> machines = domainMachines(service.getAllMachines());

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

        Machine m = domainMachines(service.getAllMachines()).get(0);

        assertThat(m.lanCidr()).isEqualTo("192.168.3.0/24");
        assertThat(m.lanAddress()).isEqualTo("192.168.3.5");
    }

    @Test
    void getAllMachines_includesLanServers_lanServerHasNullWgFields() {
        lenient().when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "relay")
        ));

        List<Machine> machines = domainMachines(service.getAllMachines());

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

        List<Machine> machines = domainMachines(service.getAllMachines());

        assertThat(machines).extracting(Machine::name, Machine::type)
            .containsExactlyInAnyOrder(
                tuple("alice", MachineType.UBUNTU_SERVER),
                tuple("nas", MachineType.LAN_SERVER));
    }

    // --- SSH-access override (#307) ---

    @Test
    void setMachineSshAccess_lanServer_savesOverride_andReturnsEnabled() {
        LanServer nas = new LanServer("nas", "192.168.3.50", true, 2375);
        lenient().when(forPersistingLanServers.getAll()).thenReturn(List.of(nas));

        boolean effective = service.setMachineSshAccess("nas", false);

        assertThat(effective).isFalse();
        org.mockito.Mockito.verify(forPersistingLanServers).save(nas.withSshAccessOverride(false));
        org.mockito.Mockito.verifyNoInteractions(forUpdatingPeerConfigurations);
    }

    @Test
    void setMachineSshAccess_peer_updatesByPeerId_andReturnsEnabled() {
        lenient().when(forPersistingLanServers.getAll()).thenReturn(List.of());
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("alice-id", "alice", "10.13.13.2", "", MachineType.UBUNTU_SERVER,
                null, null, null)
        ));

        boolean effective = service.setMachineSshAccess("alice", true);

        assertThat(effective).isTrue();
        org.mockito.Mockito.verify(forUpdatingPeerConfigurations).updateSshAccess("alice-id", true);
    }

    @Test
    void setMachineSshAccess_unknownMachine_throwsNotFound() {
        lenient().when(forPersistingLanServers.getAll()).thenReturn(List.of());
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.setMachineSshAccess("ghost", true))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void setMachineSshAccess_vaierServer_writesToConfig_notPeerOrLanAdapter() {
        lenient().when(forPersistingAppConfiguration.load())
            .thenReturn(Optional.of(VaierConfig.builder().domain("example.com").build()));

        boolean effective = service.setMachineSshAccess(LanAnchor.VAIER_SERVER_NAME, false);

        assertThat(effective).isFalse();
        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        org.mockito.Mockito.verify(forPersistingAppConfiguration).save(captor.capture());
        assertThat(captor.getValue().getVaierServerSshAccess()).isFalse();
        assertThat(captor.getValue().getDomain()).isEqualTo("example.com"); // other fields preserved
        org.mockito.Mockito.verifyNoInteractions(forUpdatingPeerConfigurations);
        org.mockito.Mockito.verify(forPersistingLanServers, org.mockito.Mockito.never())
            .save(org.mockito.ArgumentMatchers.any());
    }

    // --- a machine's disk (#323 slice C) -------------------------------------------------------------
    //
    // RemoteDiskWatcher has computed this on a schedule since the disk alerts shipped — but it only ever
    // emailed about it, so the number Vaier already knew could not be looked at. The reading is taken over
    // the one SSH exec port every other remote command uses; the service orchestrates and the domain
    // decides (RemoteDiskUsage owns how df is read and what counts as over threshold).

    private static final SshTarget UNPINNED =
        new SshTarget("10.13.13.6", 22, "geir", AuthMethod.PASSWORD, "secret", null, null);

    private static final String DF_OUTPUT = """
        Filesystem     1024-blocks      Used Available Capacity Mounted on
        /dev/root         30298176  18178905  10566487      63% /
        """;

    @Test
    void diskUsage_readsDfOverTheSameSshExecPortEveryOtherCommandUses() {
        when(forResolvingSshTargets.resolve("Apalveien 5")).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(UNPINNED, "df -P /"))
            .thenReturn(new CommandResult(0, DF_OUTPUT, "", false, null));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(80);

        var usage = service.getDiskUsage("Apalveien 5");

        assertThat(usage.machineName()).isEqualTo("Apalveien 5");
        assertThat(usage.usedPercent()).isEqualTo(63);
        assertThat(usage.thresholdPercent()).isEqualTo(80);
        assertThat(usage.aboveThreshold()).isFalse();
    }

    @Test
    void diskUsage_asksTheDomainWhetherItIsOverThreshold_neverRecomputesIt() {
        // The predicate is RemoteDiskUsage.isAbove — the same one the alert email is sent from. A second
        // comparison here would be a second definition of "under pressure", and they would drift.
        when(forResolvingSshTargets.resolve("Colina 27")).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(0, "/dev/root 100 91 9 91% /", "", false, null));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(80);

        var usage = service.getDiskUsage("Colina 27");

        assertThat(usage.usedPercent()).isEqualTo(91);
        assertThat(usage.aboveThreshold()).isTrue();
    }

    @Test
    void diskUsage_pinsTheHostKeyOnFirstUse_likeEveryOtherSshPath() {
        // A machine may have its disk read before a terminal was ever opened on it, so this connect is
        // where an unpinned host gets pinned — trust-on-first-use, exactly as the shell and SFTP paths do.
        when(forResolvingSshTargets.resolve("Apalveien 5")).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(0, DF_OUTPUT, "", false, "SHA256:abc"));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(80);

        service.getDiskUsage("Apalveien 5");

        verify(forTrackingHostKeys).pin("Apalveien 5", "SHA256:abc");
    }

    @Test
    void diskUsage_thatCannotBeRead_saysSo_ratherThanReportingAnEmptyDisk() {
        // df failed (a sleeping machine, a df that exited non-zero). "Cannot tell" must never render as
        // 0% — a disk Vaier could not read is not a disk with room on it.
        when(forResolvingSshTargets.resolve("nas")).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(1, "", "df: command not found", false, null));

        assertThatThrownBy(() -> service.getDiskUsage("nas"))
            .isInstanceOf(DiskUnreadableException.class)
            .hasMessageContaining("nas");

        verify(configResolver, never()).getDiskMonitorThresholdPercent();
    }

    @Test
    void diskUsage_thatCannotBeParsed_saysSo_ratherThanGuessing() {
        when(forResolvingSshTargets.resolve("nas")).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(0, "Filesystem 1024-blocks Used Available Capacity Mounted on",
                "", false, null));

        assertThatThrownBy(() -> service.getDiskUsage("nas"))
            .isInstanceOf(DiskUnreadableException.class);
    }
}
