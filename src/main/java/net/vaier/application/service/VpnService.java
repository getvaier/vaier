package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.CreatePeerUseCase;
import net.vaier.application.DeletePeerUseCase;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.ApproveEnrolmentUseCase;
import net.vaier.application.EnrolDeviceUseCase;
import net.vaier.application.CheckStandingUseCase;
import net.vaier.application.LeaveFleetUseCase;
import net.vaier.application.ListEnrolmentRequestsUseCase;
import net.vaier.application.LookUpEnrolmentTicketUseCase;
import net.vaier.application.GenerateDockerComposeUseCase;
import net.vaier.application.GeneratePeerSetupScriptUseCase;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetServerLocationUseCase;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.GetVpnPeersUseCase;
import net.vaier.application.GetVpnPeersUseCase.VpnPeerView;
import net.vaier.application.ClaimDeviceUseCase;
import net.vaier.application.ForgetMyPositionUseCase;
import net.vaier.application.GetMyDeviceUseCase;
import net.vaier.application.ReportMyPositionUseCase;
import net.vaier.application.RefuseEnrolmentUseCase;
import net.vaier.application.ReissuePeerConfigUseCase;
import net.vaier.application.RequestEnrolmentUseCase;
import net.vaier.application.RenamePeerUseCase;
import net.vaier.application.ResolveVpnPeerIdUseCase;
import net.vaier.application.SyncLanRoutesUseCase;
import net.vaier.application.UpdateLanCidrUseCase;
import net.vaier.application.UpdatePeerDeviceCategoryUseCase;
import net.vaier.domain.DeviceCategory;
import net.vaier.config.ConfigResolver;
import net.vaier.config.ServiceNames;
import net.vaier.domain.DeviceClaim;
import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.EnrolmentVerdict;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.LanServer;
import net.vaier.domain.PeerProof;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineIntent;
import net.vaier.domain.LastServicesReached;
import net.vaier.domain.MachinePositions;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerArtifact;
import net.vaier.domain.PeerId;
import net.vaier.domain.PeerRoster;
import net.vaier.domain.Placement;
import net.vaier.domain.ReportedPosition;
import net.vaier.domain.UnidentifiedDeviceException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PeerNotFoundException;
import net.vaier.domain.ConflictException;
import net.vaier.domain.PeerSetupScript;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.ServerLocationResolver;
import net.vaier.domain.TunnelCaller;
import net.vaier.domain.VaierHostnames;
import net.vaier.domain.VpnClient;
import net.vaier.domain.VpnSubnet;
import net.vaier.domain.WireGuardKey;
import net.vaier.domain.WireGuardPeerConfig;
import net.vaier.domain.port.ForDeletingVpnPeers;
import net.vaier.domain.port.ForExecutingInContainer;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForHoldingEnrolmentRequests;
import net.vaier.domain.port.ForPersistingHostCredentials;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForPersistingLastServicesReached;
import net.vaier.domain.port.ForPersistingMachinePositions;
import net.vaier.domain.port.ForTrackingHostKeys;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForResolvingPeerIds;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.vaier.domain.port.ForGettingServerPublicKey;

@Service
@Slf4j
public class VpnService implements
    CreatePeerUseCase,
    EnrolDeviceUseCase,
    RequestEnrolmentUseCase,
    ListEnrolmentRequestsUseCase,
    ApproveEnrolmentUseCase,
    RefuseEnrolmentUseCase,
    LookUpEnrolmentTicketUseCase,
    LeaveFleetUseCase,
    CheckStandingUseCase,
    DeletePeerUseCase,
    GetVpnClientsUseCase,
    GetVpnPeersUseCase,
    ResolveVpnPeerIdUseCase,
    GetPeerConfigUseCase,
    GeneratePeerSetupScriptUseCase,
    GenerateDockerComposeUseCase,
    GetServerLocationUseCase,
    UpdateLanCidrUseCase,
    RenamePeerUseCase,
    ReissuePeerConfigUseCase,
    UpdatePeerDeviceCategoryUseCase,
    ReportMyPositionUseCase,
    ForgetMyPositionUseCase,
    ClaimDeviceUseCase,
    GetMyDeviceUseCase,
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
    private final ForResolvingPeerIds forResolvingPeerIds;
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
    private final ForPersistingHostCredentials forPersistingHostCredentials;
    private final ForTrackingHostKeys forTrackingHostKeys;
    private final ForPersistingMachinePositions forPersistingMachinePositions;
    private final ForPersistingLastServicesReached forPersistingLastServicesReached;
    private final ForHoldingEnrolmentRequests forHoldingEnrolmentRequests;
    private final ForGettingServerPublicKey forGettingServerPublicKey;

    public VpnService(ConfigResolver configResolver,
                      ForGettingVpnClients forGettingVpnClients,
                      ForResolvingPeerIds forResolvingPeerIds,
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
                      ForPersistingLanServers forPersistingLanServers,
                      ForPersistingHostCredentials forPersistingHostCredentials,
                      ForTrackingHostKeys forTrackingHostKeys,
                      ForPersistingMachinePositions forPersistingMachinePositions,
                      ForPersistingLastServicesReached forPersistingLastServicesReached,
                      ForHoldingEnrolmentRequests forHoldingEnrolmentRequests,
                      ForGettingServerPublicKey forGettingServerPublicKey) {
        this.configResolver = configResolver;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forResolvingPeerIds = forResolvingPeerIds;
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
        this.forPersistingHostCredentials = forPersistingHostCredentials;
        this.forTrackingHostKeys = forTrackingHostKeys;
        this.forPersistingMachinePositions = forPersistingMachinePositions;
        this.forPersistingLastServicesReached = forPersistingLastServicesReached;
        this.forHoldingEnrolmentRequests = forHoldingEnrolmentRequests;
        this.forGettingServerPublicKey = forGettingServerPublicKey;
    }

    // --- GetVpnClientsUseCase ---

    @Override
    public List<VpnClient> getClients() {
        return forGettingVpnClients.getClients();
    }

    // --- GetVpnPeersUseCase ---

    @Override
    public List<VpnPeerView> getVpnPeers() {
        RefreshContext context = new RefreshContext(
            // Resolve the live server render inputs once per refresh (not per peer) so the
            // out-of-date check is a pure string compare against each peer's on-disk config.
            resolveServerRenderContext(),
            forPersistingMachinePositions.getAll(),
            forPersistingLastServicesReached.getAll(),
            forPersistingReverseProxyRoutes.getReverseProxyRoutes(),
            configResolver.getDomain(),
            Instant.now());
        // Configured peers the interface has forgotten are listed too, or nothing could ever remove them.
        return PeerRoster.reconcile(forGettingVpnClients.getClients(), peerConfigProvider.getAllPeerConfigs())
            .stream()
            .map(client -> toVpnPeerView(client, context))
            .toList();
    }

    /**
     * What every peer in one refresh shares — each read once here rather than once per peer, on a list a
     * scheduler repaints on a clock.
     */
    private record RefreshContext(ServerRenderContext server, MachinePositions positions,
                                  LastServicesReached reached, List<ReverseProxyRoute> routes,
                                  String baseDomain, Instant now) {}

    /**
     * The current server-side inputs to {@link WireGuardPeerConfig#reissue}. Null when they can't
     * be read (e.g. the wg interface is down) — drift is then reported as false rather than
     * false-flagging every peer.
     */
    private record ServerRenderContext(String serverPublicKey, String serverEndpoint, String serverLanCidr) {}

    private ServerRenderContext resolveServerRenderContext() {
        try {
            return new ServerRenderContext(
                forGettingServerPublicKey.getServerPublicKey(),
                extractServerEndpoint(),
                forResolvingServerLanCidr.resolve().orElse(null));
        } catch (Exception e) {
            log.debug("Could not resolve server render context for out-of-date check: {}", e.getMessage());
            return null;
        }
    }

    private VpnPeerView toVpnPeerView(VpnClient client, RefreshContext context) {
        ServerRenderContext serverContext = context.server();
        String peerIp = client.vpnIp();
        String id = forResolvingPeerIds.resolvePeerIdByIp(peerIp);
        // The raw PeerConfiguration carries the device-category override and owns the effective-
        // category decision; the PeerConfigResult below is the existing view of the same config.
        Optional<ForGettingPeerConfigurations.PeerConfiguration> rawCfg =
            peerConfigProvider.getPeerConfigByIp(peerIp);
        // Reuse the already-loaded raw config rather than re-reading it via getPeerConfigByIp —
        // one filesystem scan/parse per peer per refresh, not two.
        Optional<GetPeerConfigUseCase.PeerConfigResult> cfg = rawCfg
            .map(c -> new GetPeerConfigUseCase.PeerConfigResult(c.id(), c.name(), c.ipAddress(),
                c.configContent(), c.peerType(), c.lanCidr(), c.lanAddress(), c.description(), c.deviceHeldKey()));
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
        boolean deviceHeldKey = rawCfg
            .map(ForGettingPeerConfigurations.PeerConfiguration::deviceHeldKey).orElse(false);
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
        // The domain owns the effective SSH-access decision (override else smart default). For a peer
        // with no on-disk config yet, derive the default from the live category + type.
        boolean sshAccess = rawCfg
            .map(ForGettingPeerConfigurations.PeerConfiguration::effectiveSshAccess)
            .orElseGet(() -> Machine.defaultSshAccess(deviceCategory, peerType));
        // Read from the stored config, never minted: a live peer with no config on disk is in no machine
        // registry, and a caller joining on a fabricated id would match nothing while looking like it could.
        MachineId machineId = rawCfg
            .map(ForGettingPeerConfigurations.PeerConfiguration::machineId)
            .orElse(null);
        // Where to draw this machine is the domain's call — including refusing to draw it at all.
        Optional<Placement> placement = Placement.decide(
            context.positions().reportedFor(machineId).orElse(null), geo.orElse(null),
            client.isConnected(), Instant.ofEpochSecond(client.latestHandshakeEpoch()), context.now());
        return VpnPeerView.builder()
            .id(id).machineId(machineId == null ? null : machineId.value()).name(name)
            .publicKey(client.publicKey()).allowedIps(client.allowedIps()).tunnelIp(peerIp)
            .endpointIp(client.endpointIp()).endpointPort(client.endpointPort())
            .latestHandshake(client.latestHandshake()).connected(client.isConnected())
            .transferRx(client.transferRx()).transferTx(client.transferTx())
            .peerType(peerType).isServer(isServer).isClient(isClient).isRelay(isRelay)
            // Empty for a peer with a Device-held key: there is nothing Vaier could hand over. The fact
            // itself travels too — it is what tells the pane a Reissue is refused for this machine.
            .availableArtifacts(PeerArtifact.forPeer(peerType, deviceHeldKey))
            .deviceHeldKey(deviceHeldKey)
            .lanCidr(lanCidr).lanAddress(lanAddress).description(description)
            .geoLocation(geo).configOutOfDate(configOutOfDate)
            .deviceCategory(deviceCategory).deviceCategoryOverridden(deviceCategoryOverridden)
            .sshAccess(sshAccess).placement(placement)
            // Which points are still worth showing is the domain's call too — retention holds for a device
            // that stopped reporting, not only for one still going.
            .positionTrail(context.positions().trailFor(machineId, context.now()))
            .lastServiceReached(lastServiceReached(machineId, context))
            .build();
    }

    /**
     * What that machine last opened, named the way the launchpad names it. Which service a machine reached
     * was decided when the access was recorded — this only reads it and asks the domain for its label, so
     * nothing here has to guess whose access was whose.
     */
    private Optional<GetVpnPeersUseCase.LastServiceReachedView> lastServiceReached(
            MachineId machineId, RefreshContext context) {
        return context.reached().forMachine(machineId)
            .map(reached -> new GetVpnPeersUseCase.LastServiceReachedView(reached.host(),
                ReverseProxyRoute.launchpadDisplayNameFor(context.routes(), reached.host(),
                    context.baseDomain()),
                reached.at()));
    }

    // --- ReportMyPositionUseCase / ForgetMyPositionUseCase / ClaimDeviceUseCase ---

    @Override
    public void reportMyPosition(String callerIp, String claimToken,
                                 Double latitude, Double longitude, Double accuracyMetres) {
        // Say who is talking, never which machine to file it under: resolving that here would leave a gap
        // in which a Forget lands, and the report would then re-create the record it just erased.
        forPersistingMachinePositions.recordReportedPosition(tunnelMachine(callerIp), claimToken,
                ReportedPosition.report(latitude, longitude, accuracyMetres, Instant.now()))
            .orElseThrow(UnidentifiedDeviceException::becauseNothingIdentifiesTheDevice);
    }

    @Override
    public void forgetMyPosition(String callerIp, String claimToken) {
        // Precedence — tunnel over claim — is the domain's; this only supplies what each evidence says.
        // Resolving here is safe in a way reporting is not: a Forget racing another write can only ever
        // erase, never file something under an identity that has just stopped existing.
        MachineId machineId = forPersistingMachinePositions.getAll()
            .reportingMachine(tunnelMachine(callerIp), claimToken)
            .orElseThrow(UnidentifiedDeviceException::becauseNothingIdentifiesTheDevice);
        forPersistingMachinePositions.remove(machineId);
    }

    @Override
    public String claimDevice(String machineId) {
        MachineId claimed = MachineId.of(machineId);
        // Vaier knows its own peers, so it says so now rather than storing a claim that could never
        // place a dot. Whether the machine is one of ours is the domain's call; this only reads the port.
        if (!ForGettingPeerConfigurations.PeerConfiguration
                .isPeerMachine(peerConfigProvider.getAllPeerConfigs(), claimed)) {
            throw new PeerNotFoundException("No peer with machine id " + claimed.value() + " to claim.");
        }
        DeviceClaim claim = DeviceClaim.mint(Instant.now());
        forPersistingMachinePositions.saveClaim(claimed, claim);
        return claim.token();
    }

    @Override
    public Optional<MachineId> myDevice(String claimToken) {
        return forPersistingMachinePositions.getAll().claimedBy(claimToken);
    }

    /**
     * The machine holding {@code callerIp} as its tunnel IP, or null when that address is not one — the
     * rule is {@link TunnelCaller}'s, this only supplies the subnet and the peer store it reads.
     */
    private MachineId tunnelMachine(String callerIp) {
        return TunnelCaller.machineFor(callerIp, vpnSubnet, peerConfigProvider).orElse(null);
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

    // --- ResolveVpnPeerIdUseCase ---

    @Override
    public String resolvePeerIdByIp(String ipAddress) {
        return forResolvingPeerIds.resolvePeerIdByIp(ipAddress);
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

        return config.map(c -> new PeerConfigResult(c.id(), c.name(), c.ipAddress(), c.configContent(), c.peerType(), c.lanCidr(), c.lanAddress(), c.description(), c.deviceHeldKey()));
    }

    @Override
    public Optional<PeerConfigResult> getPeerConfigByIp(String ipAddress) {
        return peerConfigProvider.getPeerConfigByIp(ipAddress)
                .map(c -> new PeerConfigResult(c.id(), c.name(), c.ipAddress(), c.configContent(), c.peerType(), c.lanCidr(), c.lanAddress(), c.description(), c.deviceHeldKey()));
    }

    // --- GenerateDockerComposeUseCase ---

    @Override
    public String generateWireguardClientDockerCompose(String peerId, String serverUrl, String serverPort) {
        log.info("Generating docker-compose for peer: {}", peerId);
        ForGeneratingDockerComposeFiles.DockerComposeConfig config =
            new ForGeneratingDockerComposeFiles.DockerComposeConfig(peerId, serverUrl, serverPort);
        return dockerComposeGenerator.generateWireguardClientDockerCompose(config);
    }

    // --- GeneratePeerSetupScriptUseCase ---

    @Override
    public Optional<String> generateSetupScript(String peerId, String serverUrl, String serverPort) {
        log.info("Generating setup script for peer: {}", peerId);

        return getPeerConfig(peerId).map(peerConfig -> PeerSetupScript.generate(
            peerId, peerConfig.ipAddress(), serverUrl, serverPort,
            peerConfig.configContent(), peerConfig.lanCidr(), vpnSubnet));
    }

    // --- UpdateLanCidrUseCase ---

    @Override
    public void updateLanCidr(String peerId, String lanCidr) {
        // Strict CIDR validation BEFORE any peer lookup or state change. Closes #195 —
        // keeps shell-injection payloads out of `wg set ... allowed-ips` and `ip route del`.
        // Null/blank means "clear the lanCidr" — that's allowed without validation.
        if (lanCidr != null && !lanCidr.isBlank()) {
            net.vaier.domain.Cidr.validateLanCidr(lanCidr);
        }

        ForGettingPeerConfigurations.PeerConfiguration peer = peerConfigProvider.getPeerConfigByName(peerId)
            .orElseThrow(() -> new PeerNotFoundException("Peer not found: " + peerId));

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
        forUpdatingPeerConfigurations.updateLanCidr(peerId, lanCidr);
        log.info("Updated lanCidr for peer {} to {} (server-side AllowedIPs: {})", peerId, normalized, newAllowedIps);

        syncLanRoutes();
    }

    // --- SyncLanRoutesUseCase ---

    @Override
    public void syncLanRoutes() {
        Set<String> cidrs = Set.copyOf(
            ForGettingPeerConfigurations.allLanCidrs(peerConfigProvider.getAllPeerConfigs()));
        forSyncingLanRoutes.syncLanRoutes(cidrs);
    }

    // --- DeletePeerUseCase ---

    @Override
    public void deletePeer(String peerIdentifier) {
        log.info("Deleting VPN peer: {}", peerIdentifier);

        String peerId = peerIdentifier;
        if (net.vaier.domain.Cidr.isIpv4(peerIdentifier)) {
            String resolved = forResolvingPeerIds.resolvePeerIdByIp(peerIdentifier);
            if (resolved.equals(peerIdentifier)) {
                log.error("Could not find a peer for IP: {}", peerIdentifier);
                throw new PeerNotFoundException("Peer not found for IP: " + peerIdentifier);
            }
            peerId = resolved;
            log.info("Resolved IP {} to peer id: {}", peerIdentifier, peerId);
        }

        deletePublishedServicesForPeer(peerId);

        vpnPeerDeleter.deletePeer(peerId);
        log.info("Successfully deleted peer: {}", peerId);
    }

    private void deletePublishedServicesForPeer(String peerId) {
        peerConfigProvider.getPeerConfigByName(peerId).ifPresent(config -> {
            String peerIp = config.ipAddress();
            log.info("Looking for published services pointing to peer {} (IP: {})", peerId, peerIp);

            List<ReverseProxyRoute> routes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
            routes.stream()
                .filter(ReverseProxyRoute::isVaierManaged)
                .filter(route -> peerIp.equals(route.getAddress()))
                .forEach(route -> {
                    log.info("Deleting published service {} (path: {}) pointing to peer {}",
                        route.getDomainName(), route.getPathPrefix(), peerId);
                    deletePublishedServiceUseCase.deleteService(route.getDomainName(), route.getPathPrefix());
                });
        });
    }

    // --- CreatePeerUseCase ---

    @Override
    public CreatedPeerUco createPeer(String name) {
        return createPeer(name, null, null, null, null);
    }

    @Override
    public CreatedPeerUco createPeer(String name, MachineType peerType, String lanCidr) {
        return createPeer(name, peerType, lanCidr, null, null);
    }

    @Override
    public CreatedPeerUco createPeer(String name, MachineType peerType, String lanCidr, String lanAddress) {
        return createPeer(name, peerType, lanCidr, lanAddress, null);
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
        // Read the peer configs once (a filesystem scan), for id generation.
        List<ForGettingPeerConfigurations.PeerConfiguration> allPeers = peerConfigProvider.getAllPeerConfigs();
        // A machine's name no longer has to be free (§6.22): it is a label, and every record that used to
        // hang off it hangs off a MachineId. Two houses may each have a "NAS"; Vaier tells them apart
        // because it never told them apart by name in the first place.
        //
        // The peer ID still must be unique — it is the config directory — and PeerId.generate deduplicates
        // it below. That is a filesystem constraint, not an opinion about what an operator may call things.
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

            PeerSlot slot = openPeerSlot(id);

            String clientConfig = WireGuardPeerConfig.generate(
                    privateKey, slot.ipAddress(), slot.serverPublicKey(), slot.presharedKey(),
                    slot.serverEndpoint(), resolvedType, lanCidr, lanAddress, vpnSubnet,
                    description, name, slot.serverLanCidr(), null, slot.machineId());

            writePeerConfig(id, clientConfig);

            addPeerToServer(wireguardInterface, publicKey, slot.presharedKey(), slot.ipAddress(), lanCidr);
            log.info("Peer created successfully: {} with IP {}", id, slot.ipAddress());

            return new CreatedPeerUco(id, slot.machineId(), name, slot.ipAddress(), publicKey, privateKey,
                    clientConfig, resolvedType);

        } catch (IOException | InterruptedException e) {
            log.error("Error creating peer", e);
            throw new RuntimeException("Failed to create peer: " + e.getMessage(), e);
        }
    }

    // --- EnrolDeviceUseCase (#359) ---

    @Override
    public EnrolledDeviceUco enrol(String name, String publicKey) {
        return enrolDevice(name, publicKey);
    }

    /**
     * The enrolment itself, shared by the operator-driven {@code enrol} and by {@link #approve}, so a
     * phone that came in through a join code joins on exactly the terms one that came in through the
     * console does.
     */
    private EnrolledDeviceUco enrolDevice(String name, String publicKey) {
        // Both judgements BEFORE any state change, exactly as createPeer validates a lanCidr: the key
        // goes straight into `wg set ... peer <key>`'s argv and onto disk, and a name that slugs to
        // nothing has no config directory to live in.
        WireGuardKey deviceKey = WireGuardKey.of(publicKey);
        PeerId.sanitized(name);

        List<ForGettingPeerConfigurations.PeerConfiguration> allPeers = peerConfigProvider.getAllPeerConfigs();
        Set<String> existingIds = allPeers.stream()
                .map(ForGettingPeerConfigurations.PeerConfiguration::id)
                .collect(Collectors.toSet());
        String id = PeerId.generate(name, existingIds).value();
        log.info("Enrolling device '{}' (id {}) under its own public key", name, id);

        try {
            PeerSlot slot = openPeerSlot(id);

            // Rendered with no private key and the device's public key in the metadata: the config is
            // installable by the app, which supplies the half Vaier does not have.
            // The Vaier app runs on a phone: a personal device that is not Windows. The intent -> type
            // mapping stays MachineIntent's, exactly as it is for the peer form.
            MachineType peerType = MachineIntent.PERSONAL_DEVICE.toMachineType(false);
            String clientConfig = WireGuardPeerConfig.generate(
                    null, slot.ipAddress(), slot.serverPublicKey(), slot.presharedKey(),
                    slot.serverEndpoint(), peerType, null, null, vpnSubnet,
                    null, name, slot.serverLanCidr(), null, slot.machineId(), deviceKey.value());

            writePeerConfig(id, clientConfig);

            addPeerToServer(wireguardInterface, deviceKey.value(), slot.presharedKey(), slot.ipAddress(), null);

            // The config went to the app in this response and there is nothing left to hand out — a
            // device-held key has no artefact at all. Spending the budget here is what makes the five
            // secret-bearing GETs answer 410 from the first moment.
            forTrackingPeerConfigRetrieval.markViewedIfNotAlready(id);

            log.info("Device enrolled: {} with IP {}", id, slot.ipAddress());
            return new EnrolledDeviceUco(id, slot.machineId(), name, slot.ipAddress(), deviceKey.value(),
                    clientConfig, peerType);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Error enrolling device", e);
            throw new RuntimeException("Failed to enrol device: " + e.getMessage(), e);
        }
    }

    // --- Enrolment requests: a phone waits to be approved (#359 slice 1b) ---

    @Override
    public EnrolmentRequest request(String name, String publicKey) {
        if (!EnrolmentRequest.mayOpenAnother(forHoldingEnrolmentRequests.livePending().size())) {
            throw new ConflictException("Too many phones are already waiting to join. Approve or refuse "
                + "one of them, or wait for a request to expire.");
        }
        // The key and the name are judged inside the store's open(), by the domain, before anything
        // is stored — this call is still anonymous and must be able to cost nothing.
        EnrolmentRequest opened = forHoldingEnrolmentRequests.open(name, publicKey);
        log.info("A device is waiting to join as '{}' with join code {}", opened.name(), opened.code());
        return opened;
    }

    @Override
    public List<EnrolmentRequest> pending() {
        return forHoldingEnrolmentRequests.livePending();
    }

    @Override
    public ApprovedEnrolmentUco approve(String code) {
        EnrolmentRequest request = forHoldingEnrolmentRequests.findByCode(code)
            .orElseThrow(() -> new NotFoundException("No phone is waiting with join code " + code
                + ". It may have expired — ask for a new code on the phone."));

        EnrolledDeviceUco device = enrolDevice(request.name(), request.publicKey());
        // Only once the peer really exists: a failed enrolment leaves the phone waiting, so the
        // operator can simply approve it again.
        forHoldingEnrolmentRequests.recordApproval(request.code(), device.configFile());
        log.info("Approved join code {} as peer {}", request.code(), device.id());
        return new ApprovedEnrolmentUco(request.ticket(), device);
    }

    @Override
    public Optional<EnrolmentRequest> refuse(String code) {
        Optional<EnrolmentRequest> refused = forHoldingEnrolmentRequests.remove(code);
        refused.ifPresent(request -> log.info("Refused join code {}", request.code()));
        return refused;
    }

    @Override
    public EnrolmentVerdict lookUp(String ticket) {
        return forHoldingEnrolmentRequests.findByTicket(ticket)
            .map(request -> request.verdictFor(ticket, System.currentTimeMillis()))
            .orElseGet(EnrolmentVerdict::gone);
    }

    // --- LeaveFleetUseCase: a phone removes itself (#359 slice 1b) ---

    @Override
    public boolean leave(String publicKey, String presharedKey) {
        PeerProof proof = PeerProof.of(publicKey, presharedKey);
        Optional<ForGettingPeerConfigurations.PeerConfiguration> peer =
            proof.whichPeer(peerConfigProvider.getAllPeerConfigs());
        if (peer.isEmpty()) {
            // Never says which half was wrong, and never carries either key into the log.
            log.info("A device asked to leave the fleet and proved no peer");
            return false;
        }
        log.info("Peer {} is leaving the fleet at its own request", peer.get().id());
        deletePeer(peer.get().id());
        return true;
    }

    // --- CheckStandingUseCase (#359 slice 3) ---

    @Override
    public boolean isMember(String publicKey, String presharedKey) {
        return PeerProof.of(publicKey, presharedKey)
            .whichPeer(peerConfigProvider.getAllPeerConfigs())
            .isPresent();
    }

    /**
     * Everything a peer about to join needs that is the same whether Vaier minted its keypair or the
     * device did: its tunnel address, its preshared key, the server's key and endpoint, and the identity
     * stamped into its config.
     *
     * @param machineId the one place a machine's identity is minted rather than read. It goes into the
     *                  config's {@code # VAIER:} metadata, which IS the peer's record — a config written
     *                  without one produces a peer the adapter refuses to load, so it joins the WireGuard
     *                  server and is then invisible to Vaier: no machine, no credential, no backup, and no
     *                  error to see.
     */
    private record PeerSlot(String ipAddress, String presharedKey, String serverPublicKey,
                            String serverEndpoint, String serverLanCidr, MachineId machineId) {}

    private PeerSlot openPeerSlot(String id) throws IOException, InterruptedException {
        String presharedKey = forExecutingInContainer.execute(wireguardContainerName, "wg", "genpsk").trim();
        String ipAddress = findNextAvailableIp();
        log.info("Assigned IP address {} to peer {}", ipAddress, id);
        return new PeerSlot(ipAddress, presharedKey, forGettingServerPublicKey.getServerPublicKey(),
                extractServerEndpoint(), forResolvingServerLanCidr.resolve().orElse(null),
                MachineId.generate());
    }

    private void writePeerConfig(String id, String clientConfig) throws IOException {
        Path peerDir = Paths.get(wireguardConfigPath, id);
        Files.createDirectories(peerDir);
        Path peerConfigPath = peerDir.resolve(id + ".conf");
        Files.writeString(peerConfigPath, clientConfig);
        log.info("Created client config file at {}", peerConfigPath);
    }

    // --- ReissuePeerConfigUseCase ---

    @Override
    public ReissuedPeerUco reissuePeerConfig(String peerId) {
        log.info("Reissuing config for peer: {}", peerId);
        ForGettingPeerConfigurations.PeerConfiguration peer = peerConfigProvider.getPeerConfigByName(peerId)
            .orElseThrow(() -> new PeerNotFoundException("Peer not found: " + peerId));
        // A Reissue re-renders an installable config, and for a Device-held key there is none to render:
        // the private half was minted on the device and lives only there. Refused up front rather than
        // rendered and quietly handed to nobody.
        if (peer.deviceHeldKey()) {
            throw new ConflictException(peer.name() + " made its own key, so there is no config to "
                + "reissue. Remove it and enrol it again from the app to replace the key.");
        }
        String serverPublicKey = forGettingServerPublicKey.getServerPublicKey();
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
        String publicKey = forExecutingInContainer.executeWithInput(wireguardContainerName,
            WireGuardPeerConfig.readDirective(newContent, "PrivateKey"), "wg", "pubkey").trim();

        log.info("Reissued config for peer {} (serverLanCidr: {})", peer.id(), serverLanCidr);
        return new ReissuedPeerUco(peer.id(), peer.machineId(), peer.name(), peer.ipAddress(),
            publicKey, newContent, peer.peerType());
    }

    // --- UpdatePeerDeviceCategoryUseCase ---

    @Override
    public void updatePeerDeviceCategory(String peerId, String deviceCategory) {
        // Validate the override value BEFORE any lookup or state change: a non-blank value must be a
        // valid DeviceCategory. fromString throws IllegalArgumentException (-> 400) on a bad value;
        // null/blank parses to null, meaning "clear the override". The domain owns the parse rule.
        DeviceCategory parsed = DeviceCategory.fromString(deviceCategory);

        peerConfigProvider.getPeerConfigByName(peerId)
            .orElseThrow(() -> new PeerNotFoundException("Peer not found: " + peerId));

        // Persist and log the parsed value, never the raw request string: an enum name is a fixed
        // safe token, so this can't forge multiline log entries the way raw input (e.g. "\n…") could.
        String normalized = parsed == null ? null : parsed.name();
        forUpdatingPeerConfigurations.updateDeviceCategory(peerId, normalized);
        log.info("Set device category of peer {} to '{}'", peerId, parsed == null ? "auto-detect" : parsed.name());
    }

    // --- RenamePeerUseCase ---

    @Override
    public void renamePeer(String peerId, String newName) {
        // The id is immutable — "renaming" sets the editable display name only. No config files
        // move, so live tunnels and published services are untouched.
        var peerConfig = peerConfigProvider.getPeerConfigByName(peerId)
            .orElseThrow(() -> new PeerNotFoundException("Peer not found: " + peerId));
        // The SSH vault + host-key store are keyed by the peer's *display name*, so capture the current
        // one before the rename to migrate that state to the new label (#312).
        String oldName = peerConfig.name();

        // No collision check: a display name may be anything, including something another machine is
        // already called. Nothing is keyed to it.
        forUpdatingPeerConfigurations.updateName(peerId, newName);
        log.info("Set display name of peer {} to '{}'", peerId, newName);
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

        // wg set registers the peer but installs no kernel route; without one, replies to it leave by
        // the default route. This used to be done by restarting the whole interface, which dropped
        // every other peer's tunnel each time a machine was added.
        forUpdatingServerAllowedIps.installRoutes(serverAllowedIps);
    }
}
