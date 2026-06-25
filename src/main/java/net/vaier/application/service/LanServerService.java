package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.DeleteLanServerUseCase;
import net.vaier.application.GenerateLanServerSetupScriptUseCase;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.RegisterLanServerUseCase;
import net.vaier.application.RenameLanServerUseCase;
import net.vaier.application.ResolveLanAnchorUseCase;
import net.vaier.application.UpdateLanServerDescriptionUseCase;
import net.vaier.application.UpdateLanServerDeviceCategoryUseCase;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.Machine;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.ConflictException;
import net.vaier.domain.LanServerSetupScript;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
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
    UpdateLanServerDeviceCategoryUseCase,
    GetLanServersUseCase,
    ForGettingLanServers,
    GenerateLanServerSetupScriptUseCase,
    ResolveLanAnchorUseCase {

    private final ForPersistingLanServers forPersistingLanServers;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForResolvingServerLanCidr forResolvingServerLanCidr;
    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final DeletePublishedServiceUseCase deletePublishedServiceUseCase;

    @Value("${wireguard.vpn.subnet:10.13.13.0/24}")
    private String vpnSubnet;

    public LanServerService(ForPersistingLanServers forPersistingLanServers,
                            ForGettingPeerConfigurations forGettingPeerConfigurations,
                            ForResolvingServerLanCidr forResolvingServerLanCidr,
                            ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes,
                            DeletePublishedServiceUseCase deletePublishedServiceUseCase) {
        this.forPersistingLanServers = forPersistingLanServers;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forResolvingServerLanCidr = forResolvingServerLanCidr;
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
        this.deletePublishedServiceUseCase = deletePublishedServiceUseCase;
    }

    @Override
    public void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort) {
        register(name, lanAddress, runsDocker, dockerPort, null, null);
    }

    @Override
    public void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                         String description) {
        register(name, lanAddress, runsDocker, dockerPort, description, null);
    }

    @Override
    public void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                         String description, DeviceCategory deviceCategory) {
        // Normalise inputs up front so the persisted identity matches the (trimmed) uniqueness
        // comparison rule — mirrors LanServer.renamedTo, which also trims. (Trimming only strips
        // surrounding whitespace; it does not guarantee a URL-safe name.)
        String trimmedName = name == null ? null : name.trim();
        String trimmedAddress = lanAddress == null ? null : lanAddress.trim();
        LanServer.validate(trimmedName, trimmedAddress, runsDocker, dockerPort);
        // Read the peer configs and server LAN CIDR once (both are filesystem/metadata reads) and
        // reuse them for routability and the name-collision check.
        List<PeerConfiguration> peers = forGettingPeerConfigurations.getAllPeerConfigs();
        String serverLanCidr = forResolvingServerLanCidr.resolve().orElse(null);
        if (LanAnchor.resolve(trimmedAddress, peers, serverLanCidr).isEmpty()) {
            throw new IllegalArgumentException(
                "lanAddress " + trimmedAddress + " is not inside any relay peer's lanCidr, " +
                "nor inside the Vaier server's own LAN CIDR. Set lanCidr on a relay peer first " +
                "(or, on EC2, the server LAN CIDR is auto-detected from instance metadata).");
        }
        // #284: machine names are unique across Vaier. save() upserts by name, so without this
        // guard registering a duplicate name would silently overwrite the existing machine.
        if (Machine.nameIsTaken(trimmedName, otherMachineNames(peers, forPersistingLanServers.getAll(), null))) {
            throw new ConflictException("A machine named \"" + trimmedName + "\" already exists");
        }
        log.info("Registering LAN server: {} at {} (runsDocker={}, dockerPort={})",
            trimmedName, trimmedAddress, runsDocker, dockerPort);
        forPersistingLanServers.save(
            new LanServer(trimmedName, trimmedAddress, runsDocker, dockerPort, description, deviceCategory));
    }

    @Override
    public void updateDeviceCategory(String name, String deviceCategory) {
        // Validate the override value first: a non-blank value must be a valid DeviceCategory
        // (IllegalArgumentException -> 400). Null/blank parses to null = "clear the override".
        // The domain owns the parse rule; withDeviceCategory owns carrying everything else over.
        DeviceCategory parsed = DeviceCategory.fromString(deviceCategory);
        LanServer existing = forPersistingLanServers.getAll().stream()
            .filter(s -> s.hasName(name))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("LAN server not found: " + name));
        forPersistingLanServers.save(existing.withDeviceCategory(parsed));
        log.info("Updated device category for LAN server {} to {}", forLog(existing.name()), parsed);
    }

    @Override
    public void updateDescription(String name, String description) {
        // withDescription owns the normalisation rule; the service only finds the entry and saves.
        LanServer existing = forPersistingLanServers.getAll().stream()
            .filter(s -> s.hasName(name))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("LAN server not found: " + name));
        forPersistingLanServers.save(existing.withDescription(description));
        log.info("Updated description for LAN server {}", forLog(existing.name()));
    }

    @Override
    public void delete(String name) {
        log.info("Deleting LAN server: {}", forLog(name));
        // Cascade first: a LAN server's published services are keyed on its lanAddress (LAN routes
        // are published via host.lanAddress()), so without this they'd be orphaned. Mirrors
        // VpnService.deletePeer cascading into published-service deletion via the *UseCase port.
        deletePublishedServicesForLanServer(name);
        forPersistingLanServers.deleteByName(name);
    }

    private void deletePublishedServicesForLanServer(String name) {
        LanServer.findByName(name, forPersistingLanServers.getAll()).ifPresent(server -> {
            String lanAddress = server.lanAddress();
            if (lanAddress == null || lanAddress.isBlank()) return;
            forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
                .filter(ReverseProxyRoute::isVaierManaged)
                .filter(route -> lanAddress.equals(route.getAddress()))
                .forEach(route -> {
                    log.info("Deleting published service {} (path: {}) pointing to LAN server {}",
                        route.getDomainName(), route.getPathPrefix(), forLog(name));
                    deletePublishedServiceUseCase.deleteService(route.getDomainName(), route.getPathPrefix());
                });
        });
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
            log.info("Rename no-op: LAN server {} already has that name", forLog(existing.name()));
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
        log.info("Renamed LAN server {} to {}", forLog(existing.name()), renamed.name());
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

    /**
     * Renders an operator-supplied name safe for a single log line. The lookup that precedes these
     * logs trims the request value, so a name like {@code "nas\n…"} can still reach a log statement;
     * collapsing CR/LF (and other ISO control chars) to spaces prevents forged multiline log entries.
     */
    private static String forLog(String name) {
        if (name == null) return "null";
        StringBuilder sb = new StringBuilder(name.length());
        name.codePoints().forEach(c -> {
            if (Character.isISOControl(c)) sb.append(' ');
            else sb.appendCodePoint(c);
        });
        return sb.toString();
    }
}
