package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteLanServerUseCase;
import net.vaier.application.GenerateLanServerSetupScriptUseCase;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.RegisterLanServerUseCase;
import net.vaier.application.RenameLanServerUseCase;
import net.vaier.application.ResolveLanAnchorUseCase;
import net.vaier.application.UpdateLanServerDescriptionUseCase;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.Machine;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.ConflictException;
import net.vaier.domain.LanServerSetupScript;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;

@Service
@Slf4j
public class LanServerService implements
    RegisterLanServerUseCase,
    DeleteLanServerUseCase,
    RenameLanServerUseCase,
    UpdateLanServerDescriptionUseCase,
    GetLanServersUseCase,
    ForGettingLanServers,
    GenerateLanServerSetupScriptUseCase,
    ResolveLanAnchorUseCase {

    private final ForPersistingLanServers forPersistingLanServers;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForResolvingServerLanCidr forResolvingServerLanCidr;

    @Value("${wireguard.vpn.subnet:10.13.13.0/24}")
    private String vpnSubnet;

    public LanServerService(ForPersistingLanServers forPersistingLanServers,
                            ForGettingPeerConfigurations forGettingPeerConfigurations,
                            ForResolvingServerLanCidr forResolvingServerLanCidr) {
        this.forPersistingLanServers = forPersistingLanServers;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forResolvingServerLanCidr = forResolvingServerLanCidr;
    }

    @Override
    public void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort) {
        register(name, lanAddress, runsDocker, dockerPort, null);
    }

    @Override
    public void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                         String description) {
        LanServer.validate(name, lanAddress, runsDocker, dockerPort);
        // Read the peer configs and server LAN CIDR once (both are filesystem/metadata reads) and
        // reuse them for routability and the name-collision check.
        List<PeerConfiguration> peers = forGettingPeerConfigurations.getAllPeerConfigs();
        String serverLanCidr = forResolvingServerLanCidr.resolve().orElse(null);
        if (LanAnchor.resolve(lanAddress, peers, serverLanCidr).isEmpty()) {
            throw new IllegalArgumentException(
                "lanAddress " + lanAddress + " is not inside any relay peer's lanCidr, " +
                "nor inside the Vaier server's own LAN CIDR. Set lanCidr on a relay peer first " +
                "(or, on EC2, the server LAN CIDR is auto-detected from instance metadata).");
        }
        // #284: machine names are unique across Vaier. save() upserts by name, so without this
        // guard registering a duplicate name would silently overwrite the existing machine.
        if (Machine.nameIsTaken(name, otherMachineNames(peers, forPersistingLanServers.getAll(), null))) {
            throw new ConflictException("A machine named \"" + name.trim() + "\" already exists");
        }
        log.info("Registering LAN server: {} at {} (runsDocker={}, dockerPort={})",
            name, lanAddress, runsDocker, dockerPort);
        forPersistingLanServers.save(new LanServer(name, lanAddress, runsDocker, dockerPort, description));
    }

    @Override
    public void updateDescription(String name, String description) {
        // withDescription owns the normalisation rule; the service only finds the entry and saves.
        LanServer existing = forPersistingLanServers.getAll().stream()
            .filter(s -> s.hasName(name))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("LAN server not found: " + name));
        forPersistingLanServers.save(existing.withDescription(description));
        log.info("Updated description for LAN server {}", name);
    }

    @Override
    public void delete(String name) {
        log.info("Deleting LAN server: {}", name);
        forPersistingLanServers.deleteByName(name);
    }

    @Override
    public void rename(String currentName, String newName) {
        // The naming rule and the renamed-copy live on the LanServer entity; the service only
        // orchestrates the lookup, the collision guard and the persistence calls.
        List<LanServer> all = forPersistingLanServers.getAll();
        LanServer existing = all.stream()
            .filter(s -> s.hasName(currentName))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("LAN server not found: " + currentName));

        LanServer renamed = existing.renamedTo(newName);

        if (renamed.hasName(currentName)) {
            log.info("Rename no-op: LAN server {} already has that name", currentName);
            return;
        }
        // #284: the new name must be free across every machine — other LAN servers and VPN peers.
        // Reuse the already-loaded `all` list rather than re-reading the LAN-server file.
        List<String> otherNames = otherMachineNames(
            forGettingPeerConfigurations.getAllPeerConfigs(), all, currentName);
        if (Machine.nameIsTaken(renamed.name(), otherNames)) {
            throw new ConflictException("A machine named \"" + renamed.name() + "\" already exists");
        }

        // save() upserts by name, so write the new entry then drop the old one.
        forPersistingLanServers.save(renamed);
        forPersistingLanServers.deleteByName(currentName);
        log.info("Renamed LAN server {} to {}", currentName, renamed.name());
    }

    @Override
    public List<LanServerView> getAll() {
        List<PeerConfiguration> peers = forGettingPeerConfigurations.getAllPeerConfigs();
        String serverLanCidr = forResolvingServerLanCidr.resolve().orElse(null);
        return forPersistingLanServers.getAll().stream()
            .map(s -> new LanServerView(s,
                LanAnchor.resolve(s.lanAddress(), peers, serverLanCidr).map(LanAnchor::name).orElse(null)))
            .toList();
    }

    @Override
    public Optional<LanAnchor> resolveLanAnchor(String lanAddress) {
        if (lanAddress == null || lanAddress.isBlank()) return Optional.empty();
        return LanAnchor.resolve(lanAddress,
            forGettingPeerConfigurations.getAllPeerConfigs(),
            forResolvingServerLanCidr.resolve().orElse(null));
    }

    @Override
    public Optional<String> generateSetupScript(String lanServerName) {
        // Orchestration only: read the LAN server and the inputs the domain needs from the driven
        // ports, then let the domain decide what the script must do and render it.
        return LanServer.findByName(lanServerName, forPersistingLanServers.getAll())
            .flatMap(server -> LanServerSetupScript.forHost(server,
                forGettingPeerConfigurations.getAllPeerConfigs(),
                forResolvingServerLanCidr.resolve().orElse(null), vpnSubnet));
    }

    /**
     * Names of every machine Vaier knows about — VPN peers and LAN servers — except the LAN
     * server called {@code excludeLanServerName} (pass null to exclude nothing). The caller passes
     * the already-read peer configs and LAN servers so each source is read at most once per
     * operation. Orchestration only: the domain ({@link Machine#nameIsTaken}) decides whether a
     * candidate name is free across all of Vaier.
     */
    private List<String> otherMachineNames(List<PeerConfiguration> peers, List<LanServer> lanServers,
                                           String excludeLanServerName) {
        Stream<String> peerNames = peers.stream().map(PeerConfiguration::name);
        Stream<String> lanServerNames = lanServers.stream()
            .filter(s -> excludeLanServerName == null || !s.hasName(excludeLanServerName))
            .map(LanServer::name);
        return Stream.concat(peerNames, lanServerNames).toList();
    }
}
