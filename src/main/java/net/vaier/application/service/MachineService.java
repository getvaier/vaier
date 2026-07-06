package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.SetMachineSshAccessUseCase;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.Machine;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.port.ForGettingMachines;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingVpnClients;
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
public class MachineService implements GetMachinesUseCase, SetMachineSshAccessUseCase, ForGettingMachines {

    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForGettingLanServers forGettingLanServers;
    private final ForResolvingServerLanCidr forResolvingServerLanCidr;
    private final ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    private final ForPersistingLanServers forPersistingLanServers;

    public MachineService(ForGettingPeerConfigurations forGettingPeerConfigurations,
                          ForGettingVpnClients forGettingVpnClients,
                          ForGettingLanServers forGettingLanServers,
                          ForResolvingServerLanCidr forResolvingServerLanCidr,
                          ForUpdatingPeerConfigurations forUpdatingPeerConfigurations,
                          ForPersistingLanServers forPersistingLanServers) {
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forGettingLanServers = forGettingLanServers;
        this.forResolvingServerLanCidr = forResolvingServerLanCidr;
        this.forUpdatingPeerConfigurations = forUpdatingPeerConfigurations;
        this.forPersistingLanServers = forPersistingLanServers;
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

        return result;
    }

    @Override
    public boolean setMachineSshAccess(String machineName, boolean enabled) {
        // A machine name is unique across all of Vaier (#284), so at most one machine matches.
        // Resolve to a LAN server first, else a VPN peer; either way write an explicit override via
        // the owning store's driven port. The override always wins, so the effective state == enabled.
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
