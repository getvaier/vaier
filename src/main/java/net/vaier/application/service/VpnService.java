package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.CreatePeerUseCase;
import net.vaier.application.DeletePeerUseCase;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.GenerateDockerComposeUseCase;
import net.vaier.application.GeneratePeerSetupScriptUseCase;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetServerLocationUseCase;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.GetVpnPeersUseCase;
import net.vaier.application.GetVpnPeersUseCase.VpnPeerView;
import net.vaier.application.ReissuePeerConfigUseCase;
import net.vaier.application.RenamePeerUseCase;
import net.vaier.application.ResolveVpnPeerNameUseCase;
import net.vaier.application.SyncLanRoutesUseCase;
import net.vaier.application.UpdateLanCidrUseCase;
import net.vaier.application.UpdatePeerDeviceCategoryUseCase;
import net.vaier.domain.DeviceCategory;
import net.vaier.config.ConfigResolver;
import net.vaier.config.ServiceNames;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.LanServer;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerId;
import net.vaier.domain.PeerNotFoundException;
import net.vaier.domain.ConflictException;
import net.vaier.domain.PeerSetupScript;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.ServerLocationResolver;
import net.vaier.domain.VaierHostnames;
import net.vaier.domain.VpnClient;
import net.vaier.domain.VpnSubnet;
import net.vaier.domain.WireGuardPeerConfig;
import net.vaier.domain.port.ForDeletingVpnPeers;
import net.vaier.domain.port.ForExecutingInContainer;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForResolvingPeerNames;
import net.vaier.domain.port.ForResolvingPublicHost;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import net.vaier.domain.port.ForSyncingLanRoutes;
import net.vaier.domain.port.ForTrackingPeerConfigRetrieval;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import net.vaier.domain.port.ForUpdatingServerAllowedIps;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class VpnService implements
    CreatePeerUseCase,
    DeletePeerUseCase,
    GetVpnClientsUseCase,
    GetVpnPeersUseCase,
    ResolveVpnPeerNameUseCase,
    GetPeerConfigUseCase,
    GeneratePeerSetupScriptUseCase,
    GenerateDockerComposeUseCase,
    GetServerLocationUseCase,
    UpdateLanCidrUseCase,
    RenamePeerUseCase,
    ReissuePeerConfigUseCase,
    UpdatePeerDeviceCategoryUseCase,
    SyncLanRoutesUseCase {

    @Value("${wireguard.config.path:/wireguard/config}")
    private String wireguardConfigPath;

    @Value("${wireguard.container.name:wireguard}")
    private String wireguardContainerName;

    @Value("${wireguard.vpn.subnet:10.13.13.0/24}")
    private String vpnSubnet;

    @Value("${wireguard.interface:wg0}")
    private String wireguardInterface;

    private final ConfigResolver configResolver;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForResolvingPeerNames forResolvingPeerNames;
    private final ForGettingPeerConfigurations peerConfigProvider;
    private final ForDeletingVpnPeers vpnPeerDeleter;
    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForGeneratingDockerComposeFiles dockerComposeGenerator;
    private final DeletePublishedServiceUseCase deletePublishedServiceUseCase;
    private final ForResolvingPublicHost forResolvingPublicHost;
    private final ForGeolocatingIps forGeolocatingIps;
    private final ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    private final ForUpdatingServerAllowedIps forUpdatingServerAllowedIps;
    private final ForSyncingLanRoutes forSyncingLanRoutes;
    private final ForExecutingInContainer forExecutingInContainer;
    private final ForResolvingServerLanCidr forResolvingServerLanCidr;
    private final ForTrackingPeerConfigRetrieval forTrackingPeerConfigRetrieval;
    private final ForPersistingLanServers forPersistingLanServers;

    public VpnService(ConfigResolver configResolver,
                      ForGettingVpnClients forGettingVpnClients,
                      ForResolvingPeerNames forResolvingPeerNames,
                      ForGettingPeerConfigurations peerConfigProvider,
                      ForDeletingVpnPeers vpnPeerDeleter,
                      ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes,
                      ForGeneratingDockerComposeFiles dockerComposeGenerator,
                      DeletePublishedServiceUseCase deletePublishedServiceUseCase,
                      ForResolvingPublicHost forResolvingPublicHost,
                      ForGeolocatingIps forGeolocatingIps,
                      ForUpdatingPeerConfigurations forUpdatingPeerConfigurations,
                      ForUpdatingServerAllowedIps forUpdatingServerAllowedIps,
                      ForSyncingLanRoutes forSyncingLanRoutes,
                      ForExecutingInContainer forExecutingInContainer,
                      ForResolvingServerLanCidr forResolvingServerLanCidr,
                      ForTrackingPeerConfigRetrieval forTrackingPeerConfigRetrieval,
                      ForPersistingLanServers forPersistingLanServers) {
        this.configResolver = configResolver;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forResolvingPeerNames = forResolvingPeerNames;
        this.peerConfigProvider = peerConfigProvider;
        this.vpnPeerDeleter = vpnPeerDeleter;
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
        this.dockerComposeGenerator = dockerComposeGenerator;
        this.deletePublishedServiceUseCase = deletePublishedServiceUseCase;
        this.forResolvingPublicHost = forResolvingPublicHost;
        this.forGeolocatingIps = forGeolocatingIps;
        this.forUpdatingPeerConfigurations = forUpdatingPeerConfigurations;
        this.forUpdatingServerAllowedIps = forUpdatingServerAllowedIps;
        this.forSyncingLanRoutes = forSyncingLanRoutes;
        this.forExecutingInContainer = forExecutingInContainer;
        this.forResolvingServerLanCidr = forResolvingServerLanCidr;
        this.forTrackingPeerConfigRetrieval = forTrackingPeerConfigRetrieval;
        this.forPersistingLanServers = forPersistingLanServers;
    }

    // --- GetVpnClientsUseCase ---

    @Override
    public List<VpnClient> getClients() {
        return forGettingVpnClients.getClients();
    }

    // --- GetVpnPeersUseCase ---

    @Override
    public List<VpnPeerView> getVpnPeers() {
        // Resolve the live server render inputs once per refresh (not per peer) so the
        // out-of-date check is a pure string compare against each peer's on-disk config.
        ServerRenderContext serverContext = resolveServerRenderContext();
        return forGettingVpnClients.getClients().stream()
            .map(client -> toVpnPeerView(client, serverContext))
            .toList();
    }

    /**
     * The current server-side inputs to {@link WireGuardPeerConfig#reissue}. Null when they can't
     * be read (e.g. the wg interface is down) — drift is then reported as false rather than
     * false-flagging every peer.
     */
    private record ServerRenderContext(String serverPublicKey, String serverEndpoint, String serverLanCidr) {}

    private ServerRenderContext resolveServerRenderContext() {
        try {
            return new ServerRenderContext(
                getServerPublicKey(wireguardInterface),
                extractServerEndpoint(),
                forResolvingServerLanCidr.resolve().orElse(null));
        } catch (Exception e) {
            log.debug("Could not resolve server render context for out-of-date check: {}", e.getMessage());
            return null;
        }
    }

    private VpnPeerView toVpnPeerView(VpnClient client, ServerRenderContext serverContext) {
        String peerIp = client.vpnIp();
        String id = forResolvingPeerNames.resolvePeerNameByIp(peerIp);
        // The raw PeerConfiguration carries the device-category override and owns the effective-
        // category decision; the PeerConfigResult below is the existing view of the same config.
        Optional<ForGettingPeerConfigurations.PeerConfiguration> rawCfg =
            peerConfigProvider.getPeerConfigByIp(peerIp);
        Optional<GetPeerConfigUseCase.PeerConfigResult> cfg = getPeerConfigByIp(peerIp);
        MachineType peerType = cfg.map(GetPeerConfigUseCase.PeerConfigResult::peerType)
            .orElse(MachineType.defaultType());
        String name = cfg.map(GetPeerConfigUseCase.PeerConfigResult::name)
            .orElseGet(() -> PeerId.display(id));
        String lanCidr = cfg.map(GetPeerConfigUseCase.PeerConfigResult::lanCidr).orElse(null);
        String lanAddress = cfg.map(GetPeerConfigUseCase.PeerConfigResult::lanAddress).orElse(null);
        String description = cfg.map(GetPeerConfigUseCase.PeerConfigResult::description).orElse(null);
        Optional<GeoLocation> geo = (client.endpointIp() != null && !client.endpointIp().isBlank())
            ? forGeolocatingIps.locate(client.endpointIp())
            : Optional.empty();
        boolean isServer = peerType.isServerType();
        boolean isClient = peerType.isVpnPeer() && !isServer;
        boolean isRelay = isServer && lanCidr != null && !lanCidr.isBlank();
        boolean configOutOfDate = cfg.isPresent() && serverContext != null
            && WireGuardPeerConfig.isOutOfDate(
                cfg.get().configContent(), peerType, lanCidr, lanAddress, description,
                storedName(cfg.get().configContent(), name), serverContext.serverPublicKey(),
                serverContext.serverEndpoint(), vpnSubnet, serverContext.serverLanCidr());
        // The domain owns the effective-category decision (override else detect). For a peer with no
        // on-disk config yet, detect from the live name + type (no override, never overridden).
        DeviceCategory deviceCategory = rawCfg
            .map(ForGettingPeerConfigurations.PeerConfiguration::effectiveDeviceCategory)
            .orElseGet(() -> DeviceCategory.detect(name, peerType, null));
        boolean deviceCategoryOverridden = rawCfg
            .map(ForGettingPeerConfigurations.PeerConfiguration::deviceCategoryOverridden)
            .orElse(false);
        return new VpnPeerView(
            id, name, client.publicKey(), client.allowedIps(), peerIp,
            client.endpointIp(), client.endpointPort(), client.latestHandshake(),
            client.isConnected(), client.transferRx(), client.transferTx(),
            peerType, isServer, isClient, isRelay,
            net.vaier.domain.PeerArtifact.forPeerType(peerType),
            lanCidr, lanAddress, description, geo, configOutOfDate,
            deviceCategory, deviceCategoryOverridden);
    }

    /**
     * The name to feed back into a re-render: the raw stored name, or null when the config carries
     * none. {@code effectiveName} substitutes the humanised id when there's no stored name, so
     * passing it through would embed a name the on-disk config lacks and falsely report drift.
     */
    private static String storedName(String configContent, String effectiveName) {
        return configContent.contains("\"name\"") ? effectiveName : null;
    }

    // --- GetServerLocationUseCase ---

    @Override
    public Optional<ServerLocation> getServerLocation() {
        // The domain owns the four-tier fallback + A-vs-CNAME branching; the service supplies the
        // public-host port and a DNS resolver, then runs the geolocation lookup on the result.
        // The LAN CIDR is independent — populated even when geolocation fails, so the Vaier-server
        // machine card can render it before the geoip DB is in place.
        String lanCidr = forResolvingServerLanCidr.resolve().orElse(null);
        Optional<ServerLocation> located = ServerLocationResolver
            .resolve(forResolvingPublicHost, this::resolveHostnameToIp, configResolver.getDomain())
            .flatMap(host -> forGeolocatingIps.locate(host.publicIp())
                .map(geo -> new ServerLocation(host.displayLabel(),
                    geo.latitude(), geo.longitude(), geo.city(), geo.country(), lanCidr)));
        if (located.isPresent()) return located;
        // No geolocation, but lanCidr alone is useful to surface. Drop empty-everything to empty.
        if (lanCidr == null) return Optional.empty();
        return Optional.of(new ServerLocation(null, null, null, null, null, lanCidr));
    }

    private String resolveHostnameToIp(String hostname) {
        try {
            return java.net.InetAddress.getByName(hostname).getHostAddress();
        } catch (java.net.UnknownHostException e) {
            log.debug("Could not resolve public host {} to an IP: {}", hostname, e.getMessage());
            return null;
        }
    }

    // --- ResolveVpnPeerNameUseCase ---

    @Override
    public String resolvePeerNameByIp(String ipAddress) {
        return forResolvingPeerNames.resolvePeerNameByIp(ipAddress);
    }

    // --- GetPeerConfigUseCase ---

    @Override
    public Optional<PeerConfigResult> getPeerConfig(String peerIdentifier) {
        log.info("Fetching config for peer: {}", peerIdentifier);

        Optional<ForGettingPeerConfigurations.PeerConfiguration> config;
        if (net.vaier.domain.Cidr.isIpv4(peerIdentifier)) {
            config = peerConfigProvider.getPeerConfigByIp(peerIdentifier);
        } else {
            config = peerConfigProvider.getPeerConfigByName(peerIdentifier);
        }

        return config.map(c -> new PeerConfigResult(c.id(), c.name(), c.ipAddress(), c.configContent(), c.peerType(), c.lanCidr(), c.lanAddress(), c.description()));
    }

    @Override
    public Optional<PeerConfigResult> getPeerConfigByIp(String ipAddress) {
        return peerConfigProvider.getPeerConfigByIp(ipAddress)
                .map(c -> new PeerConfigResult(c.id(), c.name(), c.ipAddress(), c.configContent(), c.peerType(), c.lanCidr(), c.lanAddress(), c.description()));
    }

    // --- GenerateDockerComposeUseCase ---

    @Override
    public String generateWireguardClientDockerCompose(String peerName, String serverUrl, String serverPort) {
        log.info("Generating docker-compose for peer: {}", peerName);
        ForGeneratingDockerComposeFiles.DockerComposeConfig config =
            new ForGeneratingDockerComposeFiles.DockerComposeConfig(peerName, serverUrl, serverPort);
        return dockerComposeGenerator.generateWireguardClientDockerCompose(config);
    }

    // --- GeneratePeerSetupScriptUseCase ---

    @Override
    public Optional<String> generateSetupScript(String peerName, String serverUrl, String serverPort) {
        log.info("Generating setup script for peer: {}", peerName);

        return getPeerConfig(peerName).map(peerConfig -> PeerSetupScript.generate(
            peerName, peerConfig.ipAddress(), serverUrl, serverPort,
            peerConfig.configContent(), peerConfig.lanCidr(), vpnSubnet));
    }

    // --- UpdateLanCidrUseCase ---

    @Override
    public void updateLanCidr(String peerName, String lanCidr) {
        // Strict CIDR validation BEFORE any peer lookup or state change. Closes #195 —
        // keeps shell-injection payloads out of `wg set ... allowed-ips` and `ip route del`.
        // Null/blank means "clear the lanCidr" — that's allowed without validation.
        if (lanCidr != null && !lanCidr.isBlank()) {
            net.vaier.domain.Cidr.validateLanCidr(lanCidr);
        }

        ForGettingPeerConfigurations.PeerConfiguration peer = peerConfigProvider.getPeerConfigByName(peerName)
            .orElseThrow(() -> new PeerNotFoundException("Peer not found: " + peerName));

        String normalized = (lanCidr == null || lanCidr.isBlank()) ? null : lanCidr.trim();

        if (normalized != null) {
            ForGettingPeerConfigurations.PeerConfiguration conflict =
                ForGettingPeerConfigurations.PeerConfiguration
                    .lanCidrOwner(peerConfigProvider.getAllPeerConfigs(), normalized, peer.id())
                    .orElse(null);
            if (conflict != null) {
                throw new ConflictException(
                    "LAN CIDR " + normalized + " already owned by peer " + conflict.name());
            }
        }

        String newAllowedIps = WireGuardPeerConfig.serverAllowedIps(peer.ipAddress(), normalized);
        forUpdatingServerAllowedIps.setPeerAllowedIps(peer.ipAddress(), newAllowedIps);
        forUpdatingPeerConfigurations.updateLanCidr(peerName, lanCidr);
        log.info("Updated lanCidr for peer {} to {} (server-side AllowedIPs: {})", peerName, normalized, newAllowedIps);

        syncLanRoutes();
    }

    // --- SyncLanRoutesUseCase ---

    @Override
    public void syncLanRoutes() {
        Set<String> cidrs = peerConfigProvider.getAllPeerConfigs().stream()
            .map(ForGettingPeerConfigurations.PeerConfiguration::lanCidr)
            .filter(c -> c != null && !c.isBlank())
            .map(String::trim)
            .collect(Collectors.toSet());
        forSyncingLanRoutes.syncLanRoutes(cidrs);
    }

    // --- DeletePeerUseCase ---

    @Override
    public void deletePeer(String peerIdentifier) {
        log.info("Deleting VPN peer: {}", peerIdentifier);

        String peerName = peerIdentifier;
        if (net.vaier.domain.Cidr.isIpv4(peerIdentifier)) {
            String resolvedName = forResolvingPeerNames.resolvePeerNameByIp(peerIdentifier);
            if (resolvedName.equals(peerIdentifier)) {
                log.error("Could not find peer name for IP: {}", peerIdentifier);
                throw new PeerNotFoundException("Peer not found for IP: " + peerIdentifier);
            }
            peerName = resolvedName;
            log.info("Resolved IP {} to peer name: {}", peerIdentifier, peerName);
        }

        deletePublishedServicesForPeer(peerName);

        vpnPeerDeleter.deletePeer(peerName);
        log.info("Successfully deleted peer: {}", peerName);
    }

    private void deletePublishedServicesForPeer(String peerName) {
        peerConfigProvider.getPeerConfigByName(peerName).ifPresent(config -> {
            String peerIp = config.ipAddress();
            log.info("Looking for published services pointing to peer {} (IP: {})", peerName, peerIp);

            List<ReverseProxyRoute> routes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
            routes.stream()
                .filter(route -> peerIp.equals(route.getAddress()))
                .forEach(route -> {
                    log.info("Deleting published service {} (path: {}) pointing to peer {}",
                        route.getDomainName(), route.getPathPrefix(), peerName);
                    deletePublishedServiceUseCase.deleteService(route.getDomainName(), route.getPathPrefix());
                });
        });
    }

    // --- CreatePeerUseCase ---

    @Override
    public CreatedPeerUco createPeer(String peerName) {
        return createPeer(peerName, null, null, null, null);
    }

    @Override
    public CreatedPeerUco createPeer(String peerName, MachineType peerType, String lanCidr) {
        return createPeer(peerName, peerType, lanCidr, null, null);
    }

    @Override
    public CreatedPeerUco createPeer(String peerName, MachineType peerType, String lanCidr, String lanAddress) {
        return createPeer(peerName, peerType, lanCidr, lanAddress, null);
    }

    @Override
    public CreatedPeerUco createPeer(String name, MachineType peerType, String lanCidr, String lanAddress,
                                     String description) {
        // Strict CIDR validation BEFORE any state change. Closes #195 — keeps shell-injection
        // payloads out of `wg set ... allowed-ips` and `ip route del` even though those sinks
        // are now argv-style.
        if (lanCidr != null && !lanCidr.isBlank()) {
            net.vaier.domain.Cidr.validateLanCidr(lanCidr);
        }
        MachineType resolvedType = peerType != null ? peerType : MachineType.defaultType();
        // Read the peer configs once (a filesystem scan) and reuse for both the name-collision
        // check and id generation.
        List<ForGettingPeerConfigurations.PeerConfiguration> allPeers = peerConfigProvider.getAllPeerConfigs();
        // #284: machine names are unique across Vaier — a new peer may not reuse the name of any
        // existing peer or LAN server. Checked before any key/IP/state is created.
        if (Machine.nameIsTaken(name, otherMachineNames(allPeers, null))) {
            throw new ConflictException("A machine named \"" + name.trim() + "\" already exists");
        }
        // The id is the slug of the operator-typed name, deduplicated against existing peers and
        // frozen for the life of the peer (its config directory name). The typed name is kept
        // verbatim as the editable display label.
        Set<String> existingIds = allPeers.stream()
                .map(ForGettingPeerConfigurations.PeerConfiguration::id)
                .collect(Collectors.toSet());
        String id = PeerId.generate(name, existingIds).value();
        log.info("Creating peer '{}' (id {}) on interface {} (peerType: {}, lanCidr: {}, lanAddress: {})",
                name, id, wireguardInterface, resolvedType, lanCidr, lanAddress);

        try {
            String privateKey = forExecutingInContainer.execute(wireguardContainerName, "wg", "genkey").trim();
            log.info("Generated private key for peer {}", id);

            String publicKey = forExecutingInContainer
                .executeWithInput(wireguardContainerName, privateKey, "wg", "pubkey").trim();
            log.info("Generated public key for peer {}: {}", id, publicKey);

            String presharedKey = forExecutingInContainer.execute(wireguardContainerName, "wg", "genpsk").trim();
            log.info("Generated preshared key for peer {}", id);

            String ipAddress = findNextAvailableIp();
            log.info("Assigned IP address {} to peer {}", ipAddress, id);

            String serverPublicKey = getServerPublicKey(wireguardInterface);
            String serverEndpoint = extractServerEndpoint();

            Path peerDir = Paths.get(wireguardConfigPath, id);
            Files.createDirectories(peerDir);

            String serverLanCidr = forResolvingServerLanCidr.resolve().orElse(null);
            String clientConfig = WireGuardPeerConfig.generate(
                    privateKey, ipAddress, serverPublicKey, presharedKey, serverEndpoint, resolvedType, lanCidr, lanAddress, vpnSubnet,
                    description, name, serverLanCidr);

            Path peerConfigPath = peerDir.resolve(id + ".conf");
            Files.writeString(peerConfigPath, clientConfig);
            log.info("Created client config file at {}", peerConfigPath);

            addPeerToServer(wireguardInterface, publicKey, presharedKey, ipAddress, lanCidr);
            log.info("Added peer to server configuration");

            log.info("Peer created successfully: {} with IP {}", id, ipAddress);

            return new CreatedPeerUco(id, name, ipAddress, publicKey, privateKey, clientConfig, resolvedType);

        } catch (IOException | InterruptedException e) {
            log.error("Error creating peer", e);
            throw new RuntimeException("Failed to create peer: " + e.getMessage(), e);
        }
    }

    // --- ReissuePeerConfigUseCase ---

    @Override
    public ReissuedPeerUco reissuePeerConfig(String peerId) {
        log.info("Reissuing config for peer: {}", peerId);
        ForGettingPeerConfigurations.PeerConfiguration peer = peerConfigProvider.getPeerConfigByName(peerId)
            .orElseThrow(() -> new PeerNotFoundException("Peer not found: " + peerId));
        try {
            String serverPublicKey = getServerPublicKey(wireguardInterface);
            String serverEndpoint = extractServerEndpoint();
            String serverLanCidr = forResolvingServerLanCidr.resolve().orElse(null);

            // Re-render from current logic, preserving the keypair/PSK/tunnel IP baked into the
            // on-disk config. Pass the raw stored name (null when absent) so the metadata round-trips.
            // Pass the raw device-category override only (null when not overridden) — never the
            // effective category — so a non-overridden peer's reissued metadata stays free of the
            // key and keeps auto-detecting.
            String deviceCategoryOverride = peer.deviceCategory() != null
                ? peer.deviceCategory().name() : null;
            String newContent = WireGuardPeerConfig.reissue(
                peer.configContent(), peer.peerType(), peer.lanCidr(), peer.lanAddress(),
                peer.description(), storedName(peer.configContent(), peer.name()),
                serverPublicKey, serverEndpoint, vpnSubnet, serverLanCidr, deviceCategoryOverride);

            forUpdatingPeerConfigurations.rewriteConfig(peer.id(), newContent);
            // Deliberate operator-initiated re-exposure: re-open the one-shot retrieval budget.
            forTrackingPeerConfigRetrieval.resetViewed(peer.id());

            // The peer's public key is derived from its preserved private key — no server-side
            // mutation, so the live tunnel and the wg0.conf [Peer] entry are untouched.
            String privateKey = WireGuardPeerConfig.readDirective(newContent, "PrivateKey");
            String publicKey = forExecutingInContainer
                .executeWithInput(wireguardContainerName, privateKey, "wg", "pubkey").trim();

            log.info("Reissued config for peer {} (serverLanCidr: {})", peer.id(), serverLanCidr);
            return new ReissuedPeerUco(peer.id(), peer.name(), peer.ipAddress(), publicKey,
                newContent, peer.peerType());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to reissue config for peer " + peerId + ": " + e.getMessage(), e);
        }
    }

    // --- UpdatePeerDeviceCategoryUseCase ---

    @Override
    public void updatePeerDeviceCategory(String peerId, String deviceCategory) {
        // Validate the override value BEFORE any lookup or state change: a non-blank value must be a
        // valid DeviceCategory. fromString throws IllegalArgumentException (-> 400) on a bad value;
        // null/blank parses to null, meaning "clear the override". The domain owns the parse rule.
        DeviceCategory.fromString(deviceCategory);

        peerConfigProvider.getPeerConfigByName(peerId)
            .orElseThrow(() -> new PeerNotFoundException("Peer not found: " + peerId));

        forUpdatingPeerConfigurations.updateDeviceCategory(peerId, deviceCategory);
        log.info("Set device category of peer {} to '{}'", peerId, deviceCategory);
    }

    // --- RenamePeerUseCase ---

    @Override
    public void renamePeer(String peerId, String newName) {
        // The id is immutable — "renaming" sets the editable display name only. No config files
        // move, so live tunnels and published services are untouched.
        peerConfigProvider.getPeerConfigByName(peerId)
            .orElseThrow(() -> new PeerNotFoundException("Peer not found: " + peerId));

        // #284: the resulting *effective* display label must be free across every other machine.
        // Clearing the name (blank newName) reverts the peer to its humanised-id fallback, which is
        // itself a label subject to the uniqueness rule — so check that fallback too, never skip it.
        String effectiveLabel = (newName == null || newName.isBlank()) ? PeerId.display(peerId) : newName;
        if (Machine.nameIsTaken(effectiveLabel, otherMachineNames(peerConfigProvider.getAllPeerConfigs(), peerId))) {
            throw new ConflictException("A machine named \"" + effectiveLabel.trim() + "\" already exists");
        }

        forUpdatingPeerConfigurations.updateName(peerId, newName);
        log.info("Set display name of peer {} to '{}'", peerId, newName);
    }

    /**
     * Names of every machine Vaier knows about — VPN peers and LAN servers — except the peer with
     * id {@code excludePeerId} (pass null to exclude nothing). The caller passes the already-read
     * peer configs so the create/rename path scans the peer files only once; LAN-server names are
     * read raw via {@code ForPersistingLanServers} (no anchor resolution — names are all we need).
     * Orchestration only: the domain ({@link Machine#nameIsTaken}) decides whether a candidate
     * name is free across all of Vaier (#284).
     */
    private List<String> otherMachineNames(
            List<ForGettingPeerConfigurations.PeerConfiguration> peers, String excludePeerId) {
        Stream<String> peerNames = peers.stream()
            .filter(p -> excludePeerId == null || !p.id().equals(excludePeerId))
            .map(ForGettingPeerConfigurations.PeerConfiguration::name);
        Stream<String> lanServerNames = forPersistingLanServers.getAll().stream()
            .map(LanServer::name);
        return Stream.concat(peerNames, lanServerNames).toList();
    }

    private String findNextAvailableIp() throws IOException {
        // Service-side I/O: collect every peer's assigned tunnel IP from its .conf on disk.
        List<String> assignedIps = new ArrayList<>();
        Path configPath = Paths.get(wireguardConfigPath);
        if (Files.exists(configPath)) {
            try (var stream = Files.list(configPath)) {
                stream.filter(Files::isDirectory)
                        .forEach(peerDir -> {
                            try {
                                Path confFile = peerDir.resolve(peerDir.getFileName() + ".conf");
                                if (Files.exists(confFile)) {
                                    String ip = WireGuardPeerConfig.readIpAddress(Files.readString(confFile));
                                    if (!ip.isEmpty()) {
                                        assignedIps.add(ip);
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("Error reading peer config: {}", e.getMessage());
                            }
                        });
            }
        }
        // The domain owns the allocation rule (one past the highest octet, never the server's .1).
        return new VpnSubnet(vpnSubnet).nextAvailableIp(assignedIps);
    }

    private String getServerPublicKey(String interfaceName) throws IOException, InterruptedException {
        log.info("Getting server public key from running interface {}", interfaceName);
        String output = forExecutingInContainer.execute(wireguardContainerName, "wg", "show", interfaceName, "public-key");
        String publicKey = output.trim();
        log.info("Got server public key from interface: {}", publicKey);
        return publicKey;
    }

    private String extractServerEndpoint() {
        String domain = configResolver.getDomain();
        String serverUrl;

        if (domain != null && !domain.isEmpty()) {
            serverUrl = new VaierHostnames(domain).vaierServerFqdn();
        } else {
            serverUrl = System.getenv().getOrDefault("SERVERURL", "vaier.eilertsen.family");
        }

        String serverPort = System.getenv().getOrDefault("SERVERPORT", ServiceNames.DEFAULT_WG_PORT);
        return serverUrl + ":" + serverPort;
    }

    private void addPeerToServer(String interfaceName, String publicKey, String presharedKey,
                                 String ipAddress, String lanCidr)
            throws IOException, InterruptedException {
        // PSK file written via shell-free `sh -c "echo ... > file"` pattern: the input
        // to that shell is internally generated (`wg genpsk` output, base64 only —
        // no shell metacharacters), and the file path is Java-controlled. Kept as
        // sh-c here only because the alternative requires shared-volume coordination
        // between vaier and wireguard containers; user-supplied lanCidr never reaches
        // this sink.
        String pskFile = "/tmp/psk_" + System.currentTimeMillis();
        forExecutingInContainer.execute(wireguardContainerName, "sh", "-c", "echo '" + presharedKey + "' > " + pskFile);

        String serverAllowedIps = WireGuardPeerConfig.serverAllowedIps(ipAddress, lanCidr);

        // Argv-style — no shell, so user-supplied lanCidr cannot break out of `allowed-ips`.
        // Closes #195.
        String output = forExecutingInContainer.execute(wireguardContainerName, "wg", "set", interfaceName,
            "peer", publicKey, "preshared-key", pskFile, "allowed-ips", serverAllowedIps);
        log.info("Add peer output: {}", output);

        forExecutingInContainer.execute(wireguardContainerName, "rm", "-f", pskFile);

        String saveOutput = forExecutingInContainer.execute(wireguardContainerName, "wg-quick", "save", interfaceName);
        log.info("Save config output: {}", saveOutput);

        forExecutingInContainer.restartWithMasqueradeSidecar(wireguardContainerName);
        log.info("WireGuard service restarted to apply NAT rules");
    }
}
