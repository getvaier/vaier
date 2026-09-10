package net.vaier.application.service;

import net.vaier.application.GetMachineDiskUsageUseCase.MachineFilesystemUco;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.CommandOutcome;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DiskUnreadableException;
import net.vaier.domain.DiskWatch;
import net.vaier.domain.LanServer;
import net.vaier.domain.Machine;
import net.vaier.domain.ClaudeAccount;
import net.vaier.domain.ClaudeSignInState;
import net.vaier.domain.ClaudeSignInStatus;
import net.vaier.domain.EffectiveUser;
import net.vaier.domain.MachineDiskStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineNetworks;
import net.vaier.domain.MachineType;
import net.vaier.domain.SshTarget;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForCachingMachineNetworks;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForHoldingClaudeSignInStandings;
import net.vaier.domain.port.ForHoldingMachineDiskStandings;
import net.vaier.domain.port.ForReadingMachineNetworks;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingDiskWatches;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForResolvingVaierServerIdentity;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MachineServiceTest {

    private static final java.util.Map<MachineId, Optional<String>> NAMES_BY_ID = new java.util.HashMap<>();

    private static MachineId mid(String name) {
        MachineId id = TestMachineIds.of(name);
        NAMES_BY_ID.put(id, Optional.of(name));
        return id;
    }

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
    @Mock ForPersistingDiskWatches forPersistingDiskWatches;
    @Mock ConfigResolver configResolver;
    @Mock ForResolvingVaierServerIdentity forResolvingVaierServerIdentity;
    @Mock ForReadingMachineNetworks forReadingMachineNetworks;
    @Mock ForCachingMachineNetworks forCachingMachineNetworks;
    @Mock ForHoldingMachineDiskStandings forHoldingMachineDiskStandings;
    @Mock ForHoldingClaudeSignInStandings forHoldingClaudeSignInStandings;
    @Mock ForPublishingEvents forPublishingEvents;

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
            forTrackingHostKeys, forPersistingDiskWatches,
            forResolvingVaierServerIdentity, forReadingMachineNetworks, forCachingMachineNetworks,
            forHoldingMachineDiskStandings, forHoldingClaudeSignInStandings, forPublishingEvents,
            configResolver);
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
        lenient().when(forResolvingVaierServerIdentity.identity())
            .thenReturn(mid(LanAnchor.VAIER_SERVER_NAME));
        LanServer nas = new LanServer("nas", "192.168.3.50", true, 2375, null, null, null, mid("nas"));
        lenient().when(forPersistingLanServers.getAll()).thenReturn(List.of(nas));

        boolean effective = service.setMachineSshAccess(mid("nas"), false);

        assertThat(effective).isFalse();
        org.mockito.Mockito.verify(forPersistingLanServers).save(nas.withSshAccessOverride(false));
        org.mockito.Mockito.verifyNoInteractions(forUpdatingPeerConfigurations);
    }

    @Test
    void setMachineSshAccess_peer_updatesByPeerId_andReturnsEnabled() {
        lenient().when(forResolvingVaierServerIdentity.identity())
            .thenReturn(mid(LanAnchor.VAIER_SERVER_NAME));
        lenient().when(forPersistingLanServers.getAll()).thenReturn(List.of());
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("alice-id", "alice", "10.13.13.2", "", MachineType.UBUNTU_SERVER,
                null, null, null, null, null, mid("alice"), null)
        ));

        boolean effective = service.setMachineSshAccess(mid("alice"), true);

        assertThat(effective).isTrue();
        org.mockito.Mockito.verify(forUpdatingPeerConfigurations).updateSshAccess("alice-id", true);
    }

    @Test
    void setMachineSshAccess_unknownMachine_throwsNotFound() {
        lenient().when(forResolvingVaierServerIdentity.identity())
            .thenReturn(mid(LanAnchor.VAIER_SERVER_NAME));
        lenient().when(forPersistingLanServers.getAll()).thenReturn(List.of());
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.setMachineSshAccess(mid("ghost"), true))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void setMachineSshAccess_vaierServer_writesToConfig_notPeerOrLanAdapter() {
        when(forResolvingVaierServerIdentity.identity()).thenReturn(mid(LanAnchor.VAIER_SERVER_NAME));
        lenient().when(forPersistingAppConfiguration.load())
            .thenReturn(Optional.of(VaierConfig.builder().domain("example.com").build()));

        boolean effective = service.setMachineSshAccess(mid(LanAnchor.VAIER_SERVER_NAME), false);

        assertThat(effective).isFalse();
        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        org.mockito.Mockito.verify(forPersistingAppConfiguration).save(captor.capture());
        assertThat(captor.getValue().getVaierServerSshAccess()).isFalse();
        assertThat(captor.getValue().getDomain()).isEqualTo("example.com"); // other fields preserved
        org.mockito.Mockito.verifyNoInteractions(forUpdatingPeerConfigurations);
        org.mockito.Mockito.verify(forPersistingLanServers, org.mockito.Mockito.never())
            .save(org.mockito.ArgumentMatchers.any());
    }

    // --- a machine's filesystems (#323 slice C, fixed by #325) ---------------------------------------
    //
    // The reading used to be one number from `df -P /` — the root filesystem and only the root filesystem.
    // On the NAS that is the 2.3 GB DSM system partition, 88% by design, while /volume1 (11.6 TB, every borg
    // backup) was invisible. So the service now reads every real filesystem, and the domain decides all of
    // it: RemoteDiskUsage owns how df is read, which rows are real, and what counts as a breach.

    /** A resolved target always knows which machine it is for; that is what gets pinned against. */
    private static final SshTarget UNPINNED =
        new SshTarget("10.13.13.6", 22, "geir", AuthMethod.PASSWORD, "secret", null, null,
            net.vaier.domain.TestMachineIds.of("Apalveien 5"));

    /** The real `df -P` from the NAS, trimmed to two of the eight aufs aliases (#325). */
    private static final String NAS_DF = """
        Filesystem             1024-blocks       Used  Available Capacity Mounted on
        /dev/md0                   2385528    1988940     277804      88% /
        tmpfs                      2021044       1988    2019056       1% /tmp
        /dev/mapper/cachedev_0   115404288     512932  114875740       1% /volume2
        /dev/mapper/cachedev_1 11614435576 4494352836 7119963956      39% /volume1
        none                   11614435576 4494352836 7119963956      39% /volume1/@docker/aufs/mnt/b5720e8
        none                   11614435576 4494352836 7119963956      39% /volume1/@docker/aufs/mnt/1e756f0
        """;

    private static final String LINUX_DF = """
        Filesystem     1024-blocks      Used Available Capacity Mounted on
        /dev/root         30298176  18178905  10566487      63% /
        """;

    @Test
    void diskUsage_readsEveryRealFilesystem_notJustRoot() {
        // The #325 regression test at the service seam: /volume1 must come back, and the pseudo-filesystems
        // and the aufs aliases must not.
        when(forResolvingSshTargets.resolve(mid("NAS"))).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(UNPINNED, "df -P"))
            .thenReturn(new CommandResult(0, NAS_DF, "", false, null));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(85);
        when(forPersistingDiskWatches.getAll()).thenReturn(List.of());

        var filesystems = service.getDiskUsage(mid("NAS"));

        assertThat(filesystems).extracting(MachineFilesystemUco::mountPoint)
            .containsExactly("/", "/volume2", "/volume1");
    }

    @Test
    void diskUsage_carriesTheSizeAndFreeSpace_soAPercentageMeansSomething() {
        when(forResolvingSshTargets.resolve(mid("NAS"))).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(0, NAS_DF, "", false, null));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(85);
        when(forPersistingDiskWatches.getAll()).thenReturn(List.of());

        var volume1 = service.getDiskUsage(mid("NAS")).stream()
            .filter(fs -> fs.mountPoint().equals("/volume1")).findFirst().orElseThrow();

        assertThat(volume1.usedPercent()).isEqualTo(39);
        assertThat(volume1.availableKb()).isEqualTo(7119963956L);
        assertThat(volume1.available()).isEqualTo("6.6 TiB");
        assertThat(volume1.size()).isEqualTo("10.8 TiB");
    }

    /**
     * Put a machine in the fleet under its own identity. Disk rows and the unreadable-disk message name the
     * machine for a person, and the service now answers that from its own fleet rather than from a registry
     * port — so a test that wants a name back has to have the machine actually exist.
     */
    private void fleetHas(String name, String ip) {
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration(name.toLowerCase().replace(' ', '-'), name, ip, "",
                MachineType.UBUNTU_SERVER, null, null, null, null, null, mid(name), null)));
    }

    @Test
    void diskUsage_readsDfOverTheSameSshExecPortEveryOtherCommandUses() {
        fleetHas("Apalveien 5", "10.13.13.6");
        when(forResolvingSshTargets.resolve(mid("Apalveien 5"))).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(UNPINNED, "df -P"))
            .thenReturn(new CommandResult(0, LINUX_DF, "", false, null));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(80);
        when(forPersistingDiskWatches.getAll()).thenReturn(List.of());

        var filesystems = service.getDiskUsage(mid("Apalveien 5"));

        assertThat(filesystems).singleElement().satisfies(root -> {
            assertThat(root.machineName()).isEqualTo("Apalveien 5");
            assertThat(root.mountPoint()).isEqualTo("/");
            assertThat(root.usedPercent()).isEqualTo(63);
            assertThat(root.thresholdPercent()).isEqualTo(80);
            assertThat(root.watched()).isTrue();
            assertThat(root.aboveThreshold()).isFalse();
        });
    }

    @Test
    void diskUsage_asksTheDomainWhetherEachFilesystemBreaches_neverRecomputesIt() {
        // The predicate is RemoteDiskUsage.breaches — the same one the alert email is sent from. A second
        // comparison here would be a second definition of "under pressure", and they would drift.
        when(forResolvingSshTargets.resolve(mid("Colina 27"))).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(0,
                "Filesystem 1024-blocks Used Available Capacity Mounted on\n"
                    + "/dev/root 100 91 9 91% /\n", "", false, null));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(80);
        when(forPersistingDiskWatches.getAll()).thenReturn(List.of());

        var root = service.getDiskUsage(mid("Colina 27")).get(0);

        assertThat(root.usedPercent()).isEqualTo(91);
        assertThat(root.aboveThreshold()).isTrue();
    }

    @Test
    void diskUsage_appliesEachFilesystemsOwnWatch_muteAndOwnThreshold() {
        // The NAS's / is 88% by design. Given its own 95% threshold it is not a breach; /volume2 is muted
        // and never breaches whatever it reads; /volume1 has no watch, so it is watched at the global 85%.
        when(forResolvingSshTargets.resolve(mid("NAS"))).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(0, NAS_DF, "", false, null));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(85);
        when(forPersistingDiskWatches.getAll()).thenReturn(List.of(
            new DiskWatch(mid("NAS"), "/", true, 95),
            new DiskWatch(mid("NAS"), "/volume2", false, null)));

        var byMount = service.getDiskUsage(mid("NAS")).stream()
            .collect(java.util.stream.Collectors.toMap(MachineFilesystemUco::mountPoint, fs -> fs));

        assertThat(byMount.get("/").usedPercent()).isEqualTo(88);
        assertThat(byMount.get("/").thresholdPercent()).isEqualTo(95);
        assertThat(byMount.get("/").aboveThreshold()).isFalse();     // 88% is normal here, and Vaier knows

        assertThat(byMount.get("/volume2").watched()).isFalse();
        assertThat(byMount.get("/volume2").aboveThreshold()).isFalse();

        assertThat(byMount.get("/volume1").watched()).isTrue();      // never silently unwatched
        assertThat(byMount.get("/volume1").thresholdPercent()).isEqualTo(85);
    }

    @Test
    void diskUsage_pinsTheHostKeyOnFirstUse_likeEveryOtherSshPath() {
        // A machine may have its disk read before a terminal was ever opened on it, so this connect is
        // where an unpinned host gets pinned — trust-on-first-use, exactly as the shell and SFTP paths do.
        when(forResolvingSshTargets.resolve(mid("Apalveien 5"))).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(0, LINUX_DF, "", false, "SHA256:abc"));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(80);
        when(forPersistingDiskWatches.getAll()).thenReturn(List.of());

        service.getDiskUsage(mid("Apalveien 5"));

        verify(forTrackingHostKeys).pin(mid("Apalveien 5"), "SHA256:abc");
    }

    @Test
    void diskUsage_thatCannotBeRead_saysSo_ratherThanReportingAnEmptyDisk() {
        // df failed (a sleeping machine, a df that exited non-zero). "Cannot tell" must never render as
        // 0% — a disk Vaier could not read is not a disk with room on it.
        fleetHas("nas", "10.13.13.9");
        when(forResolvingSshTargets.resolve(mid("nas"))).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(1, "", "df: command not found", false, null));

        assertThatThrownBy(() -> service.getDiskUsage(mid("nas")))
            .isInstanceOf(DiskUnreadableException.class)
            .hasMessageContaining("nas");

        verify(configResolver, never()).getDiskMonitorThresholdPercent();
    }

    @Test
    void diskUsage_withNoRealFilesystemAtAll_saysSo_ratherThanShowingAnEmptyList() {
        // A df that ran but yielded nothing Vaier recognises is "cannot tell", not "this machine has no
        // disks". An empty list would render as a machine with nothing to watch — the #325 silence again.
        when(forResolvingSshTargets.resolve(mid("nas"))).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(0, "Filesystem 1024-blocks Used Available Capacity Mounted on",
                "", false, null));

        assertThatThrownBy(() -> service.getDiskUsage(mid("nas")))
            .isInstanceOf(DiskUnreadableException.class);
    }

    // --- setting a filesystem's watch (#325) ----------------------------------------------------------

    @Test
    void setDiskWatch_persistsTheWatchForThatOneFilesystem() {
        service.setDiskWatch(mid("NAS"), "/", true, 95);

        var saved = ArgumentCaptor.forClass(DiskWatch.class);
        verify(forPersistingDiskWatches).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(new DiskWatch(mid("NAS"), "/", true, 95));
    }

    @Test
    void setDiskWatch_withNoThresholdOfItsOwn_fallsBackToTheGlobalOne() {
        service.setDiskWatch(mid("NAS"), "/volume1", true, null);

        var saved = ArgumentCaptor.forClass(DiskWatch.class);
        verify(forPersistingDiskWatches).save(saved.capture());
        assertThat(saved.getValue().thresholdPercent()).isNull();
    }

    @Test
    void setDiskWatch_canMuteAFilesystem() {
        service.setDiskWatch(mid("NAS"), "/volume2", false, null);

        var saved = ArgumentCaptor.forClass(DiskWatch.class);
        verify(forPersistingDiskWatches).save(saved.capture());
        assertThat(saved.getValue().watched()).isFalse();
    }

    @Test
    void getDiskWatches_resolvesAnUnconfiguredFilesystem_toWatchedByDefault() {
        // What the scheduled watcher reads. Nothing is ever silently unwatched — the failure #325 fixes is
        // silence about the disk that matters, so an unseen mount nags rather than hides.
        when(forPersistingDiskWatches.getAll()).thenReturn(List.of(new DiskWatch(mid("NAS"), "/", false, null)));

        var watches = service.getDiskWatches();

        assertThat(watches.forFilesystem(mid("NAS"), "/").watched()).isFalse();
        assertThat(watches.forFilesystem(mid("NAS"), "/volume1").watched()).isTrue();
    }

    // --- the fleet's disk standings -------------------------------------------------------------------

    @Test
    void machineDiskStandings_areServedFromWhatTheSweepAlreadyRead_withoutTouchingAMachine() {
        // The whole point: the fleet listing asks one memory-backed question and no machine is woken. If this
        // ever grew an SSH round trip, opening the Explorer would df every sleeping host in the house.
        MachineDiskStanding nas = MachineDiskStanding.builder()
            .machineId(mid("NAS")).worstMountPoint("/volume1").worstUsedPercent(91)
            .worstThresholdPercent(85).breachingFilesystems(1).watchedFilesystems(3)
            .build();
        when(forHoldingMachineDiskStandings.getAll()).thenReturn(List.of(nas));

        assertThat(service.getMachineDiskStandings()).containsExactly(nas);
        verify(forRunningSshCommands, never()).run(any(), anyString());
    }

    @Test
    void machineDiskStandings_beforeTheFirstSweep_areEmpty_neverAFleetOfHealthyDisks() {
        when(forHoldingMachineDiskStandings.getAll()).thenReturn(List.of());

        assertThat(service.getMachineDiskStandings()).isEmpty();
    }

    // --- a live reading refreshes the standing (the mute that took five minutes to land) ---------------
    //
    // The operator muted / on the NAS and the card went on naming / as the machine's worst filesystem until
    // the next five-minute sweep happened to run — a card asserting a verdict about a disk nobody was
    // judging any more. The mute itself was always right; what was missing is that the *fresh* reading this
    // very method takes right after the watch write was judged, rendered, and then dropped. Retaining it
    // costs no connection at all: the df already ran.

    @Test
    void diskUsage_retainsWhatItJustRead_soAMuteStopsNamingTheFilesystemAtOnce() {
        // The regression. / is the NAS's worst filesystem at 88% against the global 85 — until it is muted,
        // and then the machine's standing must name /volume1 without waiting for a sweep.
        when(forResolvingSshTargets.resolve(mid("NAS"))).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(0, NAS_DF, "", false, null));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(85);
        when(forPersistingDiskWatches.getAll())
            .thenReturn(List.of(new DiskWatch(mid("NAS"), "/", false, null)));

        service.getDiskUsage(mid("NAS"));

        var recorded = ArgumentCaptor.forClass(MachineDiskStanding.class);
        verify(forHoldingMachineDiskStandings).record(recorded.capture());
        assertThat(recorded.getValue().worstMountPoint()).isEqualTo("/volume1");
        assertThat(recorded.getValue().watchedFilesystems()).isEqualTo(2);
        verify(forPublishingEvents).publish(eq("vpn-peers"), eq("disk-standing-changed"), anyString());
    }

    @Test
    void diskUsage_withEveryFilesystemMuted_forgetsTheStanding_ratherThanFreezingTheCardsMark() {
        when(forResolvingSshTargets.resolve(mid("NAS"))).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(0, NAS_DF, "", false, null));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(85);
        when(forPersistingDiskWatches.getAll()).thenReturn(List.of(
            new DiskWatch(mid("NAS"), "/", false, null),
            new DiskWatch(mid("NAS"), "/volume1", false, null),
            new DiskWatch(mid("NAS"), "/volume2", false, null)));
        when(forHoldingMachineDiskStandings.forget(mid("NAS")))
            .thenReturn(Optional.of(MachineDiskStanding.builder()
                .machineId(mid("NAS")).worstMountPoint("/").worstUsedPercent(88)
                .worstThresholdPercent(85).breachingFilesystems(1).watchedFilesystems(3)
                .build()));

        service.getDiskUsage(mid("NAS"));

        verify(forHoldingMachineDiskStandings).forget(mid("NAS"));
        verify(forHoldingMachineDiskStandings, never()).record(any());
        verify(forPublishingEvents).publish(eq("vpn-peers"), eq("disk-standing-changed"), anyString());
    }

    @Test
    void diskUsage_thatFindsTheDisksExactlyAsTheyWere_doesNotWakeTheFleet() {
        // The disk pane is re-read on every view. If each view published, every open Explorer would repaint
        // every card for nothing — the same only-on-a-change discipline the sweep has always kept.
        when(forResolvingSshTargets.resolve(mid("NAS"))).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(0, NAS_DF, "", false, null));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(85);
        when(forPersistingDiskWatches.getAll()).thenReturn(List.of());
        when(forHoldingMachineDiskStandings.record(any()))
            .thenReturn(Optional.of(MachineDiskStanding.builder()
                .machineId(mid("NAS")).worstMountPoint("/").worstUsedPercent(88)
                .worstThresholdPercent(85).breachingFilesystems(1).watchedFilesystems(3)
                .build()));

        service.getDiskUsage(mid("NAS"));

        verify(forPublishingEvents, never()).publish(any(), any(), any());
    }

    @Test
    void diskUsage_thatCouldNotBeRead_leavesTheLastKnownStandingStanding() {
        // A failed df says nothing about the disks — only that Vaier could not reach them. Erasing the
        // standing would turn "asleep" into "nothing to watch here", which is the #325 silence again.
        fleetHas("nas", "10.13.13.9");
        when(forResolvingSshTargets.resolve(mid("nas"))).thenReturn(UNPINNED);
        when(forRunningSshCommands.run(any(), anyString()))
            .thenReturn(new CommandResult(1, "", "ssh: connect to host port 22: No route to host", false, null));

        assertThatThrownBy(() -> service.getDiskUsage(mid("nas")))
            .isInstanceOf(DiskUnreadableException.class);

        verify(forHoldingMachineDiskStandings, never()).record(any());
        verify(forHoldingMachineDiskStandings, never()).forget(any());
        verify(forPublishingEvents, never()).publish(any(), any(), any());
    }

    // --- detected machine networks (#333) -------------------------------------------------------------

    private static final String COLINA_IP_OUTPUT = """
        2: eth0    inet 192.168.1.10/24 brd 192.168.1.255 scope global eth0
        default via 192.168.1.1 dev eth0 proto dhcp metric 100
        """;

    @Test
    void detectMachineNetworks_readsTheMachineAndRemembersWhatItSaid() {
        MachineId colina = mid("colina");
        when(forReadingMachineNetworks.read(colina)).thenReturn(MachineNetworks.parse(COLINA_IP_OUTPUT));

        MachineNetworks detected = service.detectMachineNetworks(colina);

        assertThat(detected.lanCandidate())
            .hasValueSatisfying(n -> assertThat(n.cidr()).isEqualTo("192.168.1.0/24"));
        var recorded = ArgumentCaptor.forClass(MachineNetworks.class);
        verify(forCachingMachineNetworks).record(eq(colina), recorded.capture());
        assertThat(recorded.getValue().defaultRouteInterface()).isEqualTo("eth0");
    }

    @Test
    void detectMachineNetworks_aReadingItCouldNotTake_leavesTheLastOneAlone() {
        // The disk trackers' rule, applied here: a transient failure must never overwrite a good reading
        // with "this machine has no network", because that is what the nudge would then be judged on.
        MachineId colina = mid("colina");
        when(forReadingMachineNetworks.read(colina)).thenReturn(MachineNetworks.unknown());

        assertThat(service.detectMachineNetworks(colina).networks()).isEmpty();

        verify(forCachingMachineNetworks, never()).record(any(), any());
    }

    @Test
    void getMachineNetworks_servesTheCacheWithoutTouchingTheMachine() {
        MachineId colina = mid("colina");
        when(forCachingMachineNetworks.getNetworks(colina))
            .thenReturn(MachineNetworks.parse(COLINA_IP_OUTPUT));

        assertThat(service.getMachineNetworks(colina).uplinkAddress()).contains("192.168.1.10");

        verify(forReadingMachineNetworks, never()).read(any());
    }

    @Test
    void forgetMachineNetworksExcept_passesTheSurvivorsStraightToTheCache() {
        MachineId kept = mid("colina");

        service.forgetMachineNetworksExcept(Set.of(kept));

        verify(forCachingMachineNetworks).retainOnly(Set.of(kept));
    }

    // --- the fleet's Claude sign-in standings ----------------------------------------------------------

    @Test
    void claudeSignInStandings_areServedFromWhatTheSweepAlreadyRead_withoutTouchingAMachine() {
        // The per-machine read SSHes to a machine and asks the CLI. This one must not: it is the fleet
        // listing's ambience, and an SSH round trip per card would open the Explorer by waking the house.
        ClaudeSignInStatus nas = new ClaudeSignInStatus(mid("NAS"), "NAS", EffectiveUser.of("root"),
            ClaudeSignInState.SIGNED_IN, new ClaudeAccount("operator@example.com", "Example Org", "max"));
        when(forHoldingClaudeSignInStandings.getAll()).thenReturn(List.of(nas));

        assertThat(service.getClaudeSignInStandings()).containsExactly(nas);
        verify(forRunningSshCommands, never()).run(any(), anyString());
    }

    @Test
    void claudeSignInStandings_beforeTheFirstSweep_areEmpty_neverAFleetOfSignedOutMachines() {
        when(forHoldingClaudeSignInStandings.getAll()).thenReturn(List.of());

        assertThat(service.getClaudeSignInStandings()).isEmpty();
    }

    // --- a read-only command, for Chat (#360) ---------------------------------------------------------

    /** Orchestration only: resolve the machine, run through the one exec port, hand back the outcome. */
    @Test
    void runReadOnly_resolvesTheMachineAndRunsThroughTheSshPort() {
        MachineId id = MachineId.of("c0355605-e5a0-419a-8943-fdc5ec209958");
        SshTarget target = mock(SshTarget.class);
        when(forResolvingSshTargets.resolve(id)).thenReturn(target);
        when(forRunningSshCommands.run(target, "apt list --upgradable"))
            .thenReturn(new CommandResult(0, "curl/noble-updates 8.5.0 amd64 [upgradable from: 8.4.0]", "", false, "SHA256:x"));

        CommandOutcome outcome = service.runReadOnly(id, "apt list --upgradable");

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.output()).contains("curl/noble-updates");
        verify(target).pinOnFirstUse("SHA256:x", forTrackingHostKeys);
    }

    /** The domain refuses before anything is resolved or connected: a refused command touches no machine. */
    @Test
    void runReadOnly_refusesAChangingCommandBeforeAnySshHappens() {
        MachineId id = MachineId.of("c0355605-e5a0-419a-8943-fdc5ec209958");

        assertThatThrownBy(() -> service.runReadOnly(id, "apt install vim"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("can look, never change");
        verifyNoInteractions(forResolvingSshTargets, forRunningSshCommands);
    }
}
