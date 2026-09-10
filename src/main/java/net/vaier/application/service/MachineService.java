package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DetectMachineNetworksUseCase;
import net.vaier.application.ForgetMachineNetworksUseCase;
import net.vaier.application.GetClaudeSignInStandingsUseCase;
import net.vaier.application.GetDiskWatchesUseCase;
import net.vaier.application.GetMachineDiskStandingsUseCase;
import net.vaier.application.GetMachineDiskUsageUseCase;
import net.vaier.application.GetMachineNetworksUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetVaierServerUseCase;
import net.vaier.application.RunReadOnlyCommandUseCase;
import net.vaier.application.SetDiskWatchUseCase;
import net.vaier.application.SetMachineSshAccessUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.ClaudeSignInStatus;
import net.vaier.domain.CommandOutcome;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DiskUnreadableException;
import net.vaier.domain.DiskWatch;
import net.vaier.domain.DiskWatches;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineDiskStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.ReadOnlyCommand;
import net.vaier.domain.MachineNetworks;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.RemoteDiskUsage;
import net.vaier.domain.SshTarget;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForCachingMachineNetworks;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForHoldingClaudeSignInStandings;
import net.vaier.domain.port.ForHoldingMachineDiskStandings;
import net.vaier.domain.port.ForResolvingVaierServerIdentity;
import net.vaier.domain.port.ForPersistingDiskWatches;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForReadingMachineNetworks;
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
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MachineService implements GetMachinesUseCase, GetVaierServerUseCase,
    SetMachineSshAccessUseCase, GetMachineDiskUsageUseCase, GetMachineDiskStandingsUseCase,
    GetClaudeSignInStandingsUseCase, GetDiskWatchesUseCase, SetDiskWatchUseCase,
    DetectMachineNetworksUseCase, GetMachineNetworksUseCase, ForgetMachineNetworksUseCase,
    RunReadOnlyCommandUseCase {

    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForGettingLanServers forGettingLanServers;
    private final ForResolvingServerLanCidr forResolvingServerLanCidr;
    private final ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    private final ForPersistingLanServers forPersistingLanServers;
    private final ForPersistingAppConfiguration forPersistingAppConfiguration;
    private final ForResolvingVaierServerIdentity forResolvingVaierServerIdentity;
    private final ForResolvingSshTargets forResolvingSshTargets;
    private final ForRunningSshCommands forRunningSshCommands;
    private final ForTrackingHostKeys forTrackingHostKeys;
    private final ForPersistingDiskWatches forPersistingDiskWatches;
    private final ForReadingMachineNetworks forReadingMachineNetworks;
    private final ForCachingMachineNetworks forCachingMachineNetworks;
    private final ForHoldingMachineDiskStandings forHoldingMachineDiskStandings;
    private final ForHoldingClaudeSignInStandings forHoldingClaudeSignInStandings;
    private final ForPublishingEvents forPublishingEvents;
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
                          ForResolvingVaierServerIdentity forResolvingVaierServerIdentity,
                          ForReadingMachineNetworks forReadingMachineNetworks,
                          ForCachingMachineNetworks forCachingMachineNetworks,
                          ForHoldingMachineDiskStandings forHoldingMachineDiskStandings,
                          ForHoldingClaudeSignInStandings forHoldingClaudeSignInStandings,
                          ForPublishingEvents forPublishingEvents,
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
        this.forResolvingVaierServerIdentity = forResolvingVaierServerIdentity;
        this.forReadingMachineNetworks = forReadingMachineNetworks;
        this.forCachingMachineNetworks = forCachingMachineNetworks;
        this.forHoldingMachineDiskStandings = forHoldingMachineDiskStandings;
        this.forHoldingClaudeSignInStandings = forHoldingClaudeSignInStandings;
        this.forPublishingEvents = forPublishingEvents;
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
     *
     * <p>A successful reading is also <b>retained</b> as this machine's {@link MachineDiskStanding}, so the
     * fleet card's disk mark is refreshed by anybody looking at a machine and not only by the five-minute
     * sweep — which is what a mute, an un-mute or a moved threshold needs, since the pane re-reads the disks
     * the moment a watch is written. No extra connection: this is the very reading that was taken anyway.
     */
    @Override
    public List<MachineFilesystemUco> getDiskUsage(MachineId machineId) {
        // The rows carry a name because a person reads them; the machine is reached by identity.
        String machineName = labelFor(machineId);
        SshTarget target = forResolvingSshTargets.resolve(machineId);
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

        // Keep what was just read as this machine's standing (the domain owns both the decision and the two
        // port calls). The reading is already in hand, so no machine is woken for it — and it is what makes
        // muting a filesystem land on the fleet card immediately: the pane re-reads the disks the moment a
        // watch is written, and that reading used to be rendered and thrown away, leaving the card naming a
        // filesystem nobody was judging any more until the next five-minute sweep. Deliberately after the
        // two DiskUnreadableException throws above: a df that failed says nothing about the disks, so the
        // last known standing must stand rather than be erased.
        MachineDiskStanding.retain(machineId, filesystems, watches, globalThreshold,
            forHoldingMachineDiskStandings, forPublishingEvents);

        return filesystems.stream()
            .map(fs -> {
                // One call, one verdict — the same RemoteDiskUsage.judge the scheduled watcher asks before it
                // sends the alert email. Neither of them recombines "how full" with "how full is too full".
                RemoteDiskUsage.DiskVerdict verdict =
                    fs.judge(watches.forFilesystem(machineId, fs.mountPoint()), globalThreshold);
                return new MachineFilesystemUco(machineName, fs.device(), fs.mountPoint(),
                    fs.sizeKb(), fs.usedKb(), fs.availableKb(), fs.sizeHuman(), fs.availableHuman(),
                    fs.usedPercent(), verdict.thresholdPercent(), verdict.watched(), verdict.breaching());
            })
            .toList();
    }

    /**
     * The fleet's <b>machine disk standing</b>s — the disk half of the Explorer's machine marks.
     *
     * <p>A pure read of what {@code RemoteDiskWatcher}'s existing 5-minute sweep already found and now
     * retains. No SSH, no {@code df}, nothing woken: the reading was taken anyway and used to be thrown
     * away. A machine the sweep has not reached is simply absent from the list — the caller must draw
     * nothing for it, because an unread disk is not a disk with room on it.
     */
    @Override
    public List<MachineDiskStanding> getMachineDiskStandings() {
        return forHoldingMachineDiskStandings.getAll();
    }

    /**
     * The fleet's <b>Claude sign-in standing</b>s — the Claude half of the Explorer's machine marks.
     *
     * <p>A pure read of what {@code RemoteDiskWatcher}'s existing five-minute trip already asked. No SSH,
     * nothing woken: the per-machine {@code GetClaudeSignInStatusUseCase} runs the CLI's {@code auth
     * status} over a shell, and one of those per card would open the Explorer by waking the house. A
     * machine the sweep has not reached is simply absent, and the caller must draw nothing for it — an
     * unasked machine is not a machine that is signed out.
     */
    @Override
    public List<ClaudeSignInStatus> getClaudeSignInStandings() {
        return forHoldingClaudeSignInStandings.getAll();
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
    public void setDiskWatch(MachineId machineId, String mountPoint, boolean watched,
                             Integer thresholdPercent) {
        forPersistingDiskWatches.save(
            new DiskWatch(machineId, mountPoint, watched, thresholdPercent));
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
     * What to call a machine where a person will read it. Presentation only — nothing is found by it, and a
     * machine whose name will not resolve is labelled by its identity rather than failing the read.
     *
     * <p>Asked of this service's own fleet rather than of a registry port: naming a machine is not a
     * cross-domain question, and the port that used to answer it existed only because callers held a name
     * where they should have held an identity.
     */
    /**
     * One <b>Read-only command</b> for Chat (#360). Orchestration only: the domain judges the command — and
     * does so first, so a refused command never so much as resolves a target — then runs it through the
     * one exec port every other remote command goes through, pinning on first use as they all do.
     */
    @Override
    public CommandOutcome runReadOnly(MachineId machineId, String command) {
        ReadOnlyCommand read = ReadOnlyCommand.of(command);
        return read.runOn(forResolvingSshTargets.resolve(machineId), forRunningSshCommands, forTrackingHostKeys);
    }

    private String labelFor(MachineId machineId) {
        return getAllMachines().stream()
            .filter(m -> machineId.equals(m.id()))
            .findFirst()
            .map(Machine::name)
            .orElse(machineId.value());
    }

    // --- DetectMachineNetworksUseCase / GetMachineNetworksUseCase / ForgetMachineNetworksUseCase (#333) ---

    /**
     * Read a machine's own networks and remember them. Orchestration only: the read port asks the machine,
     * {@link MachineNetworks} decides what the answer means, and the cache port keeps it.
     *
     * <p>A reading Vaier could not take is <b>not</b> recorded. That is the same rule the disk trackers
     * follow — leave the last good state alone rather than let a transient failure look like an
     * observation — and here it matters twice over: an empty reading recorded over a real one would make a
     * machine's detected network vanish from the Explorer for five minutes at a time, and a machine
     * flickering in and out of "we know nothing about it" is worse than one Vaier is simply quiet about.
     */
    @Override
    public MachineNetworks detectMachineNetworks(MachineId machineId) {
        MachineNetworks detected = forReadingMachineNetworks.read(machineId);
        if (detected.isUnknown()) {
            log.debug("No networks read from machine {}; keeping the previous reading", machineId);
            return detected;
        }
        forCachingMachineNetworks.record(machineId, detected);
        return detected;
    }

    /** What was last detected for a machine, straight from the cache — never a fresh SSH round-trip. */
    @Override
    public MachineNetworks getMachineNetworks(MachineId machineId) {
        return forCachingMachineNetworks.getNetworks(machineId);
    }

    @Override
    public void forgetMachineNetworksExcept(Set<MachineId> machineIds) {
        forCachingMachineNetworks.retainOnly(machineIds);
    }

    private Machine vaierServerMachine() {
        VaierConfig config = forPersistingAppConfiguration.load().orElse(null);
        Boolean override = config == null ? null : config.getVaierServerSshAccess();
        return Machine.vaierServer(forResolvingVaierServerIdentity.identity(), override);
    }


    @Override
    public boolean setMachineSshAccess(MachineId machineId, boolean enabled) {
        // At most one machine matches, because an id names exactly one — this used to lean on names being
        // unique across all of Vaier (#284), which is the constraint the identity refactor removes.
        // The Vaier server is neither a peer nor a LAN server, so its override lives in the Vaier
        // config; route its write there (read-modify-write) rather than to a peer/LAN adapter (#311).
        if (machineId.isSameAs(forResolvingVaierServerIdentity.identity())) {
            VaierConfig config = forPersistingAppConfiguration.load().orElseGet(() -> VaierConfig.builder().build());
            forPersistingAppConfiguration.save(config.withVaierServerSshAccess(enabled));
            log.info("Set SSH access for the Vaier server to {}", enabled);
            return enabled;
        }
        // Otherwise resolve to a LAN server first, else a VPN peer; either way write an explicit
        // override via the owning store's driven port. The override wins, so effective == enabled.
        Optional<LanServer> lanServer = forPersistingLanServers.getAll().stream()
            .filter(server -> machineId.isSameAs(server.machineId()))
            .findFirst();
        if (lanServer.isPresent()) {
            forPersistingLanServers.save(lanServer.get().withSshAccessOverride(enabled));
            log.info("Set SSH access for LAN server {} to {}", lanServer.get().name(), enabled);
            return enabled;
        }
        Optional<PeerConfiguration> peer = forGettingPeerConfigurations.getAllPeerConfigs().stream()
            .filter(p -> machineId.isSameAs(p.machineId()))
            .findFirst();
        if (peer.isPresent()) {
            forUpdatingPeerConfigurations.updateSshAccess(peer.get().id(), enabled);
            log.info("Set SSH access for peer {} to {}", peer.get().name(), enabled);
            return enabled;
        }
        throw new NotFoundException("Machine not found: " + machineId);
    }
}
