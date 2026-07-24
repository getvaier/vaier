package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetDiskWatchesUseCase;
import net.vaier.application.GetMachineDiskUsageUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetVaierServerUseCase;
import net.vaier.application.SetDiskWatchUseCase;
import net.vaier.application.SetMachineSshAccessUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DiskUnreadableException;
import net.vaier.domain.DiskWatch;
import net.vaier.domain.DiskWatches;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.Machine;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.RemoteDiskUsage;
import net.vaier.domain.SshTarget;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingDiskWatches;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForRunningSshCommands;
import net.vaier.domain.port.ForTrackingHostKeys;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MachineService implements GetMachinesUseCase, GetVaierServerUseCase,
    SetMachineSshAccessUseCase, GetMachineDiskUsageUseCase, GetDiskWatchesUseCase,
    SetDiskWatchUseCase {

    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForGettingLanServers forGettingLanServers;
    private final ForResolvingServerLanCidr forResolvingServerLanCidr;
    private final ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    private final ForPersistingLanServers forPersistingLanServers;
    private final ForPersistingAppConfiguration forPersistingAppConfiguration;
    private final ForResolvingSshTargets forResolvingSshTargets;
    private final ForRunningSshCommands forRunningSshCommands;
    private final ForTrackingHostKeys forTrackingHostKeys;
    private final ForPersistingDiskWatches forPersistingDiskWatches;
    private final net.vaier.domain.port.ForResolvingMachineIds forResolvingMachineIds;
    private final ConfigResolver configResolver;

    public MachineService(ForGettingPeerConfigurations forGettingPeerConfigurations,
                          ForGettingVpnClients forGettingVpnClients,
                          ForGettingLanServers forGettingLanServers,
                          ForResolvingServerLanCidr forResolvingServerLanCidr,
                          ForUpdatingPeerConfigurations forUpdatingPeerConfigurations,
                          ForPersistingLanServers forPersistingLanServers,
                          ForPersistingAppConfiguration forPersistingAppConfiguration,
                          ForResolvingSshTargets forResolvingSshTargets,
                          ForRunningSshCommands forRunningSshCommands,
                          ForTrackingHostKeys forTrackingHostKeys,
                          ForPersistingDiskWatches forPersistingDiskWatches,
                          net.vaier.domain.port.ForResolvingMachineIds forResolvingMachineIds,
                          ConfigResolver configResolver) {
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forGettingLanServers = forGettingLanServers;
        this.forResolvingServerLanCidr = forResolvingServerLanCidr;
        this.forUpdatingPeerConfigurations = forUpdatingPeerConfigurations;
        this.forPersistingLanServers = forPersistingLanServers;
        this.forPersistingAppConfiguration = forPersistingAppConfiguration;
        this.forResolvingSshTargets = forResolvingSshTargets;
        this.forRunningSshCommands = forRunningSshCommands;
        this.forTrackingHostKeys = forTrackingHostKeys;
        this.forPersistingDiskWatches = forPersistingDiskWatches;
        this.forResolvingMachineIds = forResolvingMachineIds;
        this.configResolver = configResolver;
    }

    /**
     * A machine's filesystems, read now (#323 slice C, fixed by #325). The scheduled
     * {@code RemoteDiskWatcher} has taken this same reading for as long as the disk alerts have existed, but
     * it only ever emailed about it — and until #325 it read {@code df -P /}, so what it saw was the root
     * filesystem and only the root filesystem. Now it is every real filesystem, each with its size.
     *
     * <p>Orchestration only: the driven ports resolve the machine to an SSH target, run the command and load
     * the watches, and the domain decides everything — {@link RemoteDiskUsage#DF_COMMAND} is how a reading is
     * taken, {@code parseList} is how it is read (and which rows are real filesystems at all), and
     * {@code judge} is what counts as pressure. This is the same exec port every other remote command goes
     * through, so there is no second way to reach a host, and an unpinned machine is pinned on first use
     * exactly as the shell and SFTP paths pin it.
     *
     * <p>A {@code df} that failed, timed out, or yielded no real filesystem at all throws
     * {@link DiskUnreadableException}. It never returns an empty list: a machine whose disks Vaier could not
     * read is not a machine with nothing to watch.
     */
    @Override
    public List<MachineFilesystemUco> getDiskUsage(String machineName) {
        SshTarget target = forResolvingSshTargets.resolve(machineName);
        CommandResult result = forRunningSshCommands.run(target, RemoteDiskUsage.DF_COMMAND);
        target.pinOnFirstUse(result.hostKeyFingerprint(), forTrackingHostKeys);

        if (result.timedOut() || result.exitCode() != 0) {
            log.debug("df on {} failed (exit={}, timedOut={})", machineName, result.exitCode(),
                result.timedOut());
            throw new DiskUnreadableException(machineName);
        }
        List<RemoteDiskUsage> filesystems = RemoteDiskUsage.parseList(machineName, result.stdout());
        if (filesystems.isEmpty()) {
            throw new DiskUnreadableException(machineName);
        }

        int globalThreshold = configResolver.getDiskMonitorThresholdPercent();
        DiskWatches watches = getDiskWatches();
        return filesystems.stream()
            .map(fs -> {
                // One call, one verdict — the same RemoteDiskUsage.judge the scheduled watcher asks before it
                // sends the alert email. Neither of them recombines "how full" with "how full is too full".
                RemoteDiskUsage.DiskVerdict verdict =
                    fs.judge(watches.forFilesystem(machineIdOf(machineName), fs.mountPoint()), globalThreshold);
                return new MachineFilesystemUco(machineName, fs.device(), fs.mountPoint(),
                    fs.sizeKb(), fs.usedKb(), fs.availableKb(), fs.sizeHuman(), fs.availableHuman(),
                    fs.usedPercent(), verdict.thresholdPercent(), verdict.watched(), verdict.breaching());
            })
            .toList();
    }

    /**
     * The fleet's disk watches (#325). Read by the Explorer's disk Inspector and by the scheduled
     * {@code RemoteDiskWatcher} alike, so both judge a filesystem against the same watch.
     *
     * <p>Never returns "no watch" for a filesystem: {@link DiskWatches#forFilesystem} resolves an
     * unconfigured one to watched, at the global threshold.
     */
    @Override
    public DiskWatches getDiskWatches() {
        return new DiskWatches(forPersistingDiskWatches.getAll());
    }

    /**
     * Watch or mute one filesystem on one machine, optionally at its own threshold (#325) — the knob that
     * makes a fleet-wide disk alert usable, because {@code /} at 88% is normal on the NAS and an emergency on
     * Apalveien 5. The {@link DiskWatch} record validates itself; the service only persists it.
     */
    @Override
    public void setDiskWatch(String machineName, String mountPoint, boolean watched,
                             Integer thresholdPercent) {
        forPersistingDiskWatches.save(
            new DiskWatch(machineIdOf(machineName), mountPoint, watched, thresholdPercent));
    }

    @Override
    public List<Machine> getAllMachines() {
        List<PeerConfiguration> peers = forGettingPeerConfigurations.getAllPeerConfigs();
        String serverLanCidr = forResolvingServerLanCidr.resolve().orElse(null);
        Map<String, VpnClient> clientsByIp = forGettingVpnClients.getClients().stream()
            .filter(c -> c.allowedIps() != null && !c.allowedIps().isBlank())
            .collect(Collectors.toMap(
                VpnClient::vpnIp,
                c -> c,
                (a, b) -> a));

        List<Machine> result = new ArrayList<>();

        for (PeerConfiguration peer : peers) {
            result.add(Machine.fromPeer(peer, clientsByIp.get(peer.ipAddress())));
        }

        for (LanServerView view : forGettingLanServers.getAll()) {
            var server = view.server();
            String anchorLanCidr = LanAnchor.resolve(server.lanAddress(), peers, serverLanCidr)
                .map(LanAnchor::cidr)
                .orElse(null);
            result.add(Machine.fromLanServer(server, anchorLanCidr));
        }

        // The Vaier server host itself is a machine too (#311) — neither peer nor LAN server, so it's
        // appended as the singleton synthetic machine. Order among machines is not significant.
        result.add(vaierServerMachine());

        return result;
    }

    @Override
    public Machine getVaierServerMachine() {
        return vaierServerMachine();
    }

    // slice 2 (#308): the web terminal's SSH address for the Vaier-server machine is the host as seen
    // from inside the vaier container — its default-gateway host IP, or an explicit VAIER_HOST_SSH_ADDRESS
    // override. Resolved here (or in the SSH-session adapter) when the connection lands; not needed for
    // the credential/SSH-access surface in this slice.

    /**
     * The Vaier-server singleton, carrying its identity and SSH-access override from the Vaier config.
     *
     * <p>Unlike a peer or a LAN server, this machine has no creation event to mint an identity at — it
     * exists because Vaier was installed. So its {@link net.vaier.domain.MachineId} is assigned on first
     * use and persisted; every later call reads the stored one. This is initialisation, not migration:
     * a brand-new Vaier reaches this path too, and the assignment is idempotent.
     */
    /**
     * The identity of the machine named {@code machineName}. Disk watches hang off identity while REST
     * paths still carry names; this crossing goes away with the paths.
     */
    private net.vaier.domain.MachineId machineIdOf(String machineName) {
        return forResolvingMachineIds.idForName(machineName)
            .orElseThrow(() -> new NotFoundException("Machine not found: " + machineName));
    }

    private Machine vaierServerMachine() {
        VaierConfig config = forPersistingAppConfiguration.load().orElse(null);
        Boolean override = config == null ? null : config.getVaierServerSshAccess();
        return Machine.vaierServer(vaierServerMachineId(config), override);
    }

    /** The stored Vaier-server machine id, or a freshly minted one persisted back to the config. */
    private net.vaier.domain.MachineId vaierServerMachineId(VaierConfig config) {
        if (config != null && config.getVaierServerMachineId() != null) {
            try {
                return net.vaier.domain.MachineId.of(config.getVaierServerMachineId());
            } catch (IllegalArgumentException e) {
                log.error("vaierServerMachineId in the Vaier config is malformed ({}); assigning a new one."
                    + " Anything keyed to the old id will need re-pointing.", e.getMessage());
            }
        }
        net.vaier.domain.MachineId assigned = net.vaier.domain.MachineId.generate();
        VaierConfig toSave = (config == null ? VaierConfig.builder().build() : config)
            .toBuilder().vaierServerMachineId(assigned.value()).build();
        forPersistingAppConfiguration.save(toSave);
        log.info("Assigned the Vaier server its machine id {}", assigned);
        return assigned;
    }

    @Override
    public boolean setMachineSshAccess(String machineName, boolean enabled) {
        // A machine name is unique across all of Vaier (#284), so at most one machine matches.
        // The Vaier server is neither a peer nor a LAN server, so its override lives in the Vaier
        // config; route its write there (read-modify-write) rather than to a peer/LAN adapter (#311).
        if (LanAnchor.VAIER_SERVER_NAME.equals(machineName)) {
            VaierConfig config = forPersistingAppConfiguration.load().orElseGet(() -> VaierConfig.builder().build());
            forPersistingAppConfiguration.save(config.withVaierServerSshAccess(enabled));
            log.info("Set SSH access for the Vaier server to {}", enabled);
            return enabled;
        }
        // Otherwise resolve to a LAN server first, else a VPN peer; either way write an explicit
        // override via the owning store's driven port. The override wins, so effective == enabled.
        Optional<LanServer> lanServer = LanServer.findByName(machineName, forPersistingLanServers.getAll());
        if (lanServer.isPresent()) {
            forPersistingLanServers.save(lanServer.get().withSshAccessOverride(enabled));
            log.info("Set SSH access for LAN server {} to {}", machineName, enabled);
            return enabled;
        }
        Optional<PeerConfiguration> peer = forGettingPeerConfigurations.getAllPeerConfigs().stream()
            .filter(p -> machineName.equals(p.name()))
            .findFirst();
        if (peer.isPresent()) {
            forUpdatingPeerConfigurations.updateSshAccess(peer.get().id(), enabled);
            log.info("Set SSH access for peer {} to {}", machineName, enabled);
            return enabled;
        }
        throw new NotFoundException("Machine not found: " + machineName);
    }
}
