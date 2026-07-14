package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetMachineDiskUsageUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetVaierServerUseCase;
import net.vaier.application.SetMachineSshAccessUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DiskUnreadableException;
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
import net.vaier.domain.port.ForGettingMachines;
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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MachineService implements GetMachinesUseCase, GetVaierServerUseCase,
    SetMachineSshAccessUseCase, GetMachineDiskUsageUseCase, ForGettingMachines {

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
        this.configResolver = configResolver;
    }

    /**
     * A machine's disk, read now (#323 slice C). The scheduled {@code RemoteDiskWatcher} has taken this
     * same reading for as long as the disk alerts have existed, but it only ever emailed about it — so the
     * number Vaier already knew could not be looked at.
     *
     * <p>Orchestration only: the driven ports resolve the machine to an SSH target and run the command,
     * and the domain decides everything — {@link RemoteDiskUsage#DF_COMMAND} is how a reading is taken,
     * {@code parse} is how it is read, and {@code isAbove} is what counts as pressure. This is the same
     * exec port every other remote command goes through, so there is no second way to reach a host, and
     * an unpinned machine is pinned on first use exactly as the shell and SFTP paths pin it.
     *
     * <p>A {@code df} that failed, timed out or cannot be parsed throws {@link DiskUnreadableException}.
     * It never returns a zero: a disk Vaier could not read is not a disk with room on it.
     */
    @Override
    public MachineDiskUsageUco getDiskUsage(String machineName) {
        SshTarget target = forResolvingSshTargets.resolve(machineName);
        CommandResult result = forRunningSshCommands.run(target, RemoteDiskUsage.DF_COMMAND);
        target.pinOnFirstUse(machineName, result.hostKeyFingerprint(), forTrackingHostKeys);

        if (result.timedOut() || result.exitCode() != 0) {
            log.debug("df on {} failed (exit={}, timedOut={})", machineName, result.exitCode(),
                result.timedOut());
            throw new DiskUnreadableException(machineName);
        }
        RemoteDiskUsage usage = RemoteDiskUsage.parse(machineName, result.stdout())
            .orElseThrow(() -> new DiskUnreadableException(machineName));

        int threshold = configResolver.getDiskMonitorThresholdPercent();
        return new MachineDiskUsageUco(machineName, usage.usedPercent(), threshold,
            usage.isAbove(threshold));
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

    /** The Vaier-server singleton, carrying its SSH-access override read from the Vaier config. */
    private Machine vaierServerMachine() {
        Boolean override = forPersistingAppConfiguration.load()
            .map(VaierConfig::getVaierServerSshAccess)
            .orElse(null);
        return Machine.vaierServer(override);
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
