package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.GetLanServersUseCase.LanServerView;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.domain.Cidr;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingMachines;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
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

    public MachineService(ForGettingPeerConfigurations forGettingPeerConfigurations,
                          GetVpnClientsUseCase getVpnClientsUseCase,
                          GetLanServersUseCase getLanServersUseCase) {
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.getVpnClientsUseCase = getVpnClientsUseCase;
        this.getLanServersUseCase = getLanServersUseCase;
    }

    @Override
    public List<Machine> getAllMachines() {
        List<PeerConfiguration> peers = forGettingPeerConfigurations.getAllPeerConfigs();
        Map<String, VpnClient> clientsByIp = getVpnClientsUseCase.getClients().stream()
            .filter(c -> c.allowedIps() != null && !c.allowedIps().isBlank())
            .collect(Collectors.toMap(
                c -> c.allowedIps().split(",")[0].split("/")[0].trim(),
                c -> c,
                (a, b) -> a));

        List<Machine> result = new ArrayList<>();

        for (PeerConfiguration peer : peers) {
            VpnClient client = clientsByIp.get(peer.ipAddress());
            result.add(new Machine(
                peer.name(),
                peer.peerType(),
                client == null ? null : client.publicKey(),
                client == null ? null : client.allowedIps(),
                client == null ? null : client.endpointIp(),
                client == null ? null : client.endpointPort(),
                client == null ? null : client.latestHandshake(),
                client == null ? null : client.transferRx(),
                client == null ? null : client.transferTx(),
                peer.lanCidr(),
                peer.lanAddress(),
                peer.peerType().isServerType(),
                null
            ));
        }

        for (LanServerView view : getLanServersUseCase.getAll()) {
            var server = view.server();
            String relayLanCidr = relayLanCidrFor(server.lanAddress(), peers);
            result.add(new Machine(
                server.name(),
                MachineType.LAN_SERVER,
                null, null, null, null, null, null, null,
                relayLanCidr,
                server.lanAddress(),
                server.runsDocker(),
                server.dockerPort()
            ));
        }

        return result;
    }

    private String relayLanCidrFor(String lanAddress, List<PeerConfiguration> peers) {
        return peers.stream()
            .filter(p -> p.lanCidr() != null && !p.lanCidr().isBlank())
            .filter(p -> {
                try { return Cidr.parse(p.lanCidr()).contains(lanAddress); }
                catch (IllegalArgumentException e) { return false; }
            })
            .map(PeerConfiguration::lanCidr)
            .findFirst()
            .orElse(null);
    }
}
