package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetVaierServerUseCase;
import net.vaier.application.SetMachineSshAccessUseCase;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.Machine;
import net.vaier.domain.NotFoundException;
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
    SetMachineSshAccessUseCase, ForGettingMachines {

    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForGettingLanServers forGettingLanServers;
    private final ForResolvingServerLanCidr forResolvingServerLanCidr;
    private final ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    private final ForPersistingLanServers forPersistingLanServers;
    private final ForPersistingAppConfiguration forPersistingAppConfiguration;

    public MachineService(ForGettingPeerConfigurations forGettingPeerConfigurations,
                          ForGettingVpnClients forGettingVpnClients,
                          ForGettingLanServers forGettingLanServers,
                          ForResolvingServerLanCidr forResolvingServerLanCidr,
                          ForUpdatingPeerConfigurations forUpdatingPeerConfigurations,
                          ForPersistingLanServers forPersistingLanServers,
                          ForPersistingAppConfiguration forPersistingAppConfiguration) {
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forGettingLanServers = forGettingLanServers;
        this.forResolvingServerLanCidr = forResolvingServerLanCidr;
        this.forUpdatingPeerConfigurations = forUpdatingPeerConfigurations;
        this.forPersistingLanServers = forPersistingLanServers;
        this.forPersistingAppConfiguration = forPersistingAppConfiguration;
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
