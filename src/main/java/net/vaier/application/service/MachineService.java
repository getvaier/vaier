package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.GetLanServersUseCase.LanServerView;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.Machine;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingMachines;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MachineService implements GetMachinesUseCase, ForGettingMachines {

    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final GetVpnClientsUseCase getVpnClientsUseCase;
    private final GetLanServersUseCase getLanServersUseCase;
    private final ForResolvingServerLanCidr forResolvingServerLanCidr;

    public MachineService(ForGettingPeerConfigurations forGettingPeerConfigurations,
                          GetVpnClientsUseCase getVpnClientsUseCase,
                          GetLanServersUseCase getLanServersUseCase,
                          ForResolvingServerLanCidr forResolvingServerLanCidr) {
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.getVpnClientsUseCase = getVpnClientsUseCase;
        this.getLanServersUseCase = getLanServersUseCase;
        this.forResolvingServerLanCidr = forResolvingServerLanCidr;
    }

    @Override
    public List<Machine> getAllMachines() {
        List<PeerConfiguration> peers = forGettingPeerConfigurations.getAllPeerConfigs();
        String serverLanCidr = forResolvingServerLanCidr.resolve().orElse(null);
        Map<String, VpnClient> clientsByIp = getVpnClientsUseCase.getClients().stream()
            .filter(c -> c.allowedIps() != null && !c.allowedIps().isBlank())
            .collect(Collectors.toMap(
                VpnClient::vpnIp,
                c -> c,
                (a, b) -> a));

        List<Machine> result = new ArrayList<>();

        for (PeerConfiguration peer : peers) {
            result.add(Machine.fromPeer(peer, clientsByIp.get(peer.ipAddress())));
        }

        for (LanServerView view : getLanServersUseCase.getAll()) {
            var server = view.server();
            String anchorLanCidr = LanAnchor.resolve(server.lanAddress(), peers, serverLanCidr)
                .map(LanAnchor::cidr)
                .orElse(null);
            result.add(Machine.fromLanServer(server, anchorLanCidr));
        }

        return result;
    }
}
