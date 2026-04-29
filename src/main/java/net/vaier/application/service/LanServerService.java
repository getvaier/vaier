package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteLanServerUseCase;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.RegisterLanServerUseCase;
import net.vaier.domain.Cidr;
import net.vaier.domain.LanServer;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingLanServers;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class LanServerService implements
    RegisterLanServerUseCase,
    DeleteLanServerUseCase,
    GetLanServersUseCase {

    private final ForPersistingLanServers forPersistingLanServers;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;

    public LanServerService(ForPersistingLanServers forPersistingLanServers,
                            ForGettingPeerConfigurations forGettingPeerConfigurations) {
        this.forPersistingLanServers = forPersistingLanServers;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
    }

    @Override
    public void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort) {
        LanServer.validate(name, lanAddress, runsDocker, dockerPort);
        if (resolveRelay(lanAddress) == null) {
            throw new IllegalArgumentException(
                "lanAddress " + lanAddress + " is not inside any relay peer's lanCidr. " +
                "Set lanCidr on the relay peer first.");
        }
        log.info("Registering LAN server: {} at {} (runsDocker={}, dockerPort={})",
            name, lanAddress, runsDocker, dockerPort);
        forPersistingLanServers.save(new LanServer(name, lanAddress, runsDocker, dockerPort));
    }

    @Override
    public void delete(String name) {
        log.info("Deleting LAN server: {}", name);
        forPersistingLanServers.deleteByName(name);
    }

    @Override
    public List<LanServerView> getAll() {
        List<PeerConfiguration> peers = forGettingPeerConfigurations.getAllPeerConfigs();
        return forPersistingLanServers.getAll().stream()
            .map(s -> new LanServerView(s, relayNameFor(s.lanAddress(), peers)))
            .toList();
    }

    private PeerConfiguration resolveRelay(String lanAddress) {
        return resolveRelay(lanAddress, forGettingPeerConfigurations.getAllPeerConfigs());
    }

    private PeerConfiguration resolveRelay(String lanAddress, List<PeerConfiguration> peers) {
        return peers.stream()
            .filter(p -> p.lanCidr() != null && !p.lanCidr().isBlank())
            .filter(p -> {
                try { return Cidr.parse(p.lanCidr()).contains(lanAddress); }
                catch (IllegalArgumentException e) { return false; }
            })
            .findFirst().orElse(null);
    }

    private String relayNameFor(String lanAddress, List<PeerConfiguration> peers) {
        PeerConfiguration relay = resolveRelay(lanAddress, peers);
        return relay == null ? null : relay.name();
    }
}
