package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteLanDockerHostUseCase;
import net.vaier.application.GetLanDockerHostsUseCase;
import net.vaier.application.RegisterLanDockerHostUseCase;
import net.vaier.domain.Cidr;
import net.vaier.domain.LanDockerHost;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingLanDockerHosts;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class LanDockerHostService implements
    RegisterLanDockerHostUseCase,
    DeleteLanDockerHostUseCase,
    GetLanDockerHostsUseCase {

    private final ForPersistingLanDockerHosts forPersistingLanDockerHosts;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;

    public LanDockerHostService(ForPersistingLanDockerHosts forPersistingLanDockerHosts,
                                ForGettingPeerConfigurations forGettingPeerConfigurations) {
        this.forPersistingLanDockerHosts = forPersistingLanDockerHosts;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
    }

    @Override
    public void register(String name, String hostIp, int port) {
        LanDockerHost.validate(name, hostIp, port);
        if (resolveRelay(hostIp) == null) {
            throw new IllegalArgumentException(
                "Host " + hostIp + " is not inside any relay peer's lanCidr. " +
                "Set lanCidr on the relay peer first.");
        }
        log.info("Registering LAN Docker host: {} at {}:{}", name, hostIp, port);
        forPersistingLanDockerHosts.save(new LanDockerHost(name, hostIp, port));
    }

    @Override
    public void delete(String name) {
        log.info("Deleting LAN Docker host: {}", name);
        forPersistingLanDockerHosts.deleteByName(name);
    }

    @Override
    public List<LanDockerHostView> getAll() {
        List<PeerConfiguration> peers = forGettingPeerConfigurations.getAllPeerConfigs();
        return forPersistingLanDockerHosts.getAll().stream()
            .map(h -> new LanDockerHostView(h, relayNameFor(h.hostIp(), peers)))
            .toList();
    }

    private PeerConfiguration resolveRelay(String hostIp) {
        return resolveRelay(hostIp, forGettingPeerConfigurations.getAllPeerConfigs());
    }

    private PeerConfiguration resolveRelay(String hostIp, List<PeerConfiguration> peers) {
        return peers.stream()
            .filter(p -> p.lanCidr() != null && !p.lanCidr().isBlank())
            .filter(p -> {
                try { return Cidr.parse(p.lanCidr()).contains(hostIp); }
                catch (IllegalArgumentException e) { return false; }
            })
            .findFirst().orElse(null);
    }

    private String relayNameFor(String hostIp, List<PeerConfiguration> peers) {
        PeerConfiguration relay = resolveRelay(hostIp, peers);
        return relay == null ? null : relay.name();
    }
}
