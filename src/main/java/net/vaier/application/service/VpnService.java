package net.vaier.application.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.CreatePeerUseCase;
import net.vaier.application.DeletePeerUseCase;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.GenerateDockerComposeUseCase;
import net.vaier.application.GeneratePeerSetupScriptUseCase;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetServerLocationUseCase;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.ResolveVpnPeerNameUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.config.ServiceNames;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.PeerType;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.VpnClient;
import net.vaier.domain.WireGuardPeerConfig;
import net.vaier.domain.port.ForDeletingVpnPeers;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForResolvingPeerNames;
import net.vaier.domain.port.ForResolvingPublicHost;
import net.vaier.domain.port.ForResolvingPublicHost.PublicHost;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class VpnService implements
    CreatePeerUseCase,
    DeletePeerUseCase,
    GetVpnClientsUseCase,
    ResolveVpnPeerNameUseCase,
    GetPeerConfigUseCase,
    GeneratePeerSetupScriptUseCase,
    GenerateDockerComposeUseCase,
    GetServerLocationUseCase {

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
    private DockerClient dockerClient;

    public VpnService(ConfigResolver configResolver,
                      ForGettingVpnClients forGettingVpnClients,
                      ForResolvingPeerNames forResolvingPeerNames,
                      ForGettingPeerConfigurations peerConfigProvider,
                      ForDeletingVpnPeers vpnPeerDeleter,
                      ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes,
                      ForGeneratingDockerComposeFiles dockerComposeGenerator,
                      DeletePublishedServiceUseCase deletePublishedServiceUseCase,
                      ForResolvingPublicHost forResolvingPublicHost,
                      ForGeolocatingIps forGeolocatingIps) {
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
    }

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing Docker client for VpnService");

            String dockerHost = getDockerHost();
            log.info("Using Docker host: {}", dockerHost);

            DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost(dockerHost)
                    .withDockerTlsVerify(false)
                    .build();

            DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .maxConnections(100)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .responseTimeout(Duration.ofSeconds(45))
                    .build();

            dockerClient = DockerClientImpl.getInstance(config, httpClient);
            dockerClient.pingCmd().exec();
            log.info("Docker client initialized successfully for VpnService");
        } catch (Exception e) {
            log.error("Failed to initialize Docker client: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to connect to Docker daemon", e);
        }
    }

    private String getDockerHost() {
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null && !dockerHost.isEmpty()) {
            return dockerHost;
        }

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "npipe:////./pipe/docker_engine";
        } else if (os.contains("mac")) {
            return "unix:///var/run/docker.sock";
        } else {
            return "unix:///var/run/docker.sock";
        }
    }

    @PreDestroy
    public void cleanup() {
        if (dockerClient != null) {
            try {
                dockerClient.close();
            } catch (Exception e) {
                log.error("Error closing Docker client", e);
            }
        }
    }

    // --- GetVpnClientsUseCase ---

    @Override
    public List<VpnClient> getClients() {
        return forGettingVpnClients.getClients();
    }

    // --- GetServerLocationUseCase ---

    @Override
    public Optional<ServerLocation> getServerLocation() {
        // Display label: prefer the human-friendly host name (CNAME or A record) the operator sees.
        String displayHost = forResolvingPublicHost.resolve().map(PublicHost::value).orElse(null);

        // For geolocation we need the actual public IP. On EC2 the public hostname resolves to a
        // private VPC IP via split-horizon DNS, so we ask the port for a direct public IP first.
        Optional<String> publicIp = forResolvingPublicHost.resolvePublicIp();
        if (publicIp.isEmpty()) {
            // Fall back to DNS-resolving the public host (works off-EC2 when DNS is honest).
            Optional<PublicHost> host = forResolvingPublicHost.resolve();
            if (host.isPresent()) {
                String value = host.get().value();
                String resolved = host.get().type() == DnsRecordType.A ? value : resolveHostnameToIp(value);
                if (resolved != null) publicIp = Optional.of(resolved);
            }
        }
        if (publicIp.isEmpty()) {
            // Last resort: resolve vaier.<domain> via DNS.
            String domain = configResolver.getDomain();
            if (domain != null && !domain.isBlank()) {
                String fallbackHost = "vaier." + domain;
                String resolved = resolveHostnameToIp(fallbackHost);
                if (resolved != null) {
                    publicIp = Optional.of(resolved);
                    if (displayHost == null) displayHost = fallbackHost;
                }
            }
        }
        if (publicIp.isEmpty()) return Optional.empty();
        if (displayHost == null) displayHost = publicIp.get();

        final String label = displayHost;
        return forGeolocatingIps.locate(publicIp.get())
            .map(geo -> new ServerLocation(label, geo.latitude(), geo.longitude(), geo.city(), geo.country()));
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
        if (peerIdentifier.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            config = peerConfigProvider.getPeerConfigByIp(peerIdentifier);
        } else {
            config = peerConfigProvider.getPeerConfigByName(peerIdentifier);
        }

        return config.map(c -> new PeerConfigResult(c.name(), c.ipAddress(), c.configContent(), c.peerType(), c.lanCidr(), c.lanAddress()));
    }

    @Override
    public Optional<PeerConfigResult> getPeerConfigByIp(String ipAddress) {
        return peerConfigProvider.getPeerConfigByIp(ipAddress)
                .map(c -> new PeerConfigResult(c.name(), c.ipAddress(), c.configContent(), c.peerType(), c.lanCidr(), c.lanAddress()));
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

        return getPeerConfig(peerName).map(peerConfig -> {
            String vpnIp = peerConfig.ipAddress();
            String wgConfig = peerConfig.configContent();
            String lanCidr = peerConfig.lanCidr();
            return generateScript(peerName, vpnIp, serverUrl, serverPort, wgConfig, lanCidr);
        });
    }

    // --- DeletePeerUseCase ---

    @Override
    public void deletePeer(String peerIdentifier) {
        log.info("Deleting VPN peer: {}", peerIdentifier);

        String peerName = peerIdentifier;
        if (peerIdentifier.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            String resolvedName = forResolvingPeerNames.resolvePeerNameByIp(peerIdentifier);
            if (resolvedName.equals(peerIdentifier)) {
                log.error("Could not find peer name for IP: {}", peerIdentifier);
                throw new IllegalArgumentException("Peer not found for IP: " + peerIdentifier);
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
                    log.info("Deleting published service {} pointing to peer {}", route.getDomainName(), peerName);
                    deletePublishedServiceUseCase.deleteService(route.getDomainName());
                });
        });
    }

    // --- CreatePeerUseCase ---

    @Override
    public CreatedPeerUco createPeer(String peerName) {
        return createPeer(peerName, PeerType.UBUNTU_SERVER, null, null);
    }

    @Override
    public CreatedPeerUco createPeer(String peerName, PeerType peerType, String lanCidr) {
        return createPeer(peerName, peerType, lanCidr, null);
    }

    @Override
    public CreatedPeerUco createPeer(String peerName, PeerType peerType, String lanCidr, String lanAddress) {
        peerName = peerName.trim().replaceAll("[^a-zA-Z0-9_-]", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        log.info("Creating peer {} on interface {} (peerType: {}, lanCidr: {}, lanAddress: {})",
                peerName, wireguardInterface, peerType, lanCidr, lanAddress);

        try {
            String privateKey = executeInContainer("wg", "genkey").trim();
            log.info("Generated private key for peer {}", peerName);

            String publicKey = executeInContainerWithInput(privateKey, "wg", "pubkey").trim();
            log.info("Generated public key for peer {}: {}", peerName, publicKey);

            String presharedKey = executeInContainer("wg", "genpsk").trim();
            log.info("Generated preshared key for peer {}", peerName);

            String ipAddress = findNextAvailableIp();
            log.info("Assigned IP address {} to peer {}", ipAddress, peerName);

            String serverPublicKey = getServerPublicKey(wireguardInterface);
            String serverEndpoint = extractServerEndpoint();

            Path peerDir = Paths.get(wireguardConfigPath, peerName);
            Files.createDirectories(peerDir);

            String clientConfig = WireGuardPeerConfig.generate(
                    privateKey, ipAddress, serverPublicKey, presharedKey, serverEndpoint, peerType, lanCidr, lanAddress, vpnSubnet);

            Path peerConfigPath = peerDir.resolve(peerName + ".conf");
            Files.writeString(peerConfigPath, clientConfig);
            log.info("Created client config file at {}", peerConfigPath);

            addPeerToServer(wireguardInterface, publicKey, presharedKey, ipAddress, lanCidr);
            log.info("Added peer to server configuration");

            log.info("Peer created successfully: {} with IP {}", peerName, ipAddress);

            return new CreatedPeerUco(peerName, ipAddress, publicKey, privateKey, clientConfig, peerType);

        } catch (IOException | InterruptedException e) {
            log.error("Error creating peer", e);
            throw new RuntimeException("Failed to create peer: " + e.getMessage(), e);
        }
    }

    private String executeInContainer(String... command) throws IOException, InterruptedException {
        ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(wireguardContainerName)
                .withCmd(command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        dockerClient.execStartCmd(execCreateCmdResponse.getId())
                .exec(new DockerExecCallback(stdout, stderr))
                .awaitCompletion();

        String stderrStr = stderr.toString();
        if (!stderrStr.isEmpty()) {
            log.debug("Command stderr: {}", stderrStr);
        }

        return stdout.toString();
    }

    private String executeInContainerWithInput(String input, String... command) throws IOException, InterruptedException {
        String bashCommand = String.format("echo '%s' | %s", input, String.join(" ", command));
        return executeInContainer("bash", "-c", bashCommand);
    }

    private String findNextAvailableIp() throws IOException {
        Path configPath = Paths.get(wireguardConfigPath);
        AtomicInteger maxLastOctet = new AtomicInteger(1);

        if (Files.exists(configPath)) {
            try (var stream = Files.list(configPath)) {
                stream.filter(Files::isDirectory)
                        .forEach(peerDir -> {
                            try {
                                Path confFile = peerDir.resolve(peerDir.getFileName() + ".conf");
                                if (Files.exists(confFile)) {
                                    String content = Files.readString(confFile);
                                    String address = extractValue(content, "Address");
                                    if (!address.isEmpty()) {
                                        String ip = address.split("/")[0];
                                        String[] parts = ip.split("\\.");
                                        if (parts.length == 4) {
                                            int lastOctet = Integer.parseInt(parts[3]);
                                            if (lastOctet > maxLastOctet.get()) {
                                                maxLastOctet.set(lastOctet);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("Error reading peer config: {}", e.getMessage());
                            }
                        });
            }
        }

        int nextOctet = Math.max(maxLastOctet.get() + 1, 2);
        String networkAddress = vpnSubnet.split("/")[0];
        String prefix = networkAddress.substring(0, networkAddress.lastIndexOf('.') + 1);
        return prefix + nextOctet;
    }

    private String getServerPublicKey(String interfaceName) throws IOException, InterruptedException {
        log.info("Getting server public key from running interface {}", interfaceName);
        String output = executeInContainer("wg", "show", interfaceName, "public-key");
        String publicKey = output.trim();
        log.info("Got server public key from interface: {}", publicKey);
        return publicKey;
    }

    private String extractServerEndpoint() {
        String domain = configResolver.getDomain();
        String serverUrl;

        if (domain != null && !domain.isEmpty()) {
            serverUrl = "vaier." + domain;
        } else {
            serverUrl = System.getenv().getOrDefault("SERVERURL", "vaier.eilertsen.family");
        }

        String serverPort = System.getenv().getOrDefault("SERVERPORT", ServiceNames.DEFAULT_WG_PORT);
        return serverUrl + ":" + serverPort;
    }

    private void addPeerToServer(String interfaceName, String publicKey, String presharedKey,
                                 String ipAddress, String lanCidr)
            throws IOException, InterruptedException {
        String pskFile = "/tmp/psk_" + System.currentTimeMillis();
        executeInContainer("sh", "-c", "echo '" + presharedKey + "' > " + pskFile);

        String serverAllowedIps = ipAddress + "/32";
        if (lanCidr != null && !lanCidr.isBlank()) {
            serverAllowedIps = serverAllowedIps + "," + lanCidr;
        }

        String addPeerCommand = String.format(
                "wg set %s peer %s preshared-key %s allowed-ips %s",
                interfaceName, publicKey, pskFile, serverAllowedIps
        );
        log.info("Executing: {}", addPeerCommand);
        String output = executeInContainer("sh", "-c", addPeerCommand);
        log.info("Add peer output: {}", output);

        executeInContainer("rm", "-f", pskFile);

        String saveOutput = executeInContainer("wg-quick", "save", interfaceName);
        log.info("Save config output: {}", saveOutput);

        restartWireGuardService();
        log.info("WireGuard service restarted to apply NAT rules");
    }

    private void restartWireGuardService() {
        try {
            log.info("Restarting WireGuard container: {}", wireguardContainerName);
            dockerClient.restartContainerCmd(wireguardContainerName)
                    .withTimeout(30)
                    .exec();

            Thread.sleep(2000);

            log.info("WireGuard container restarted successfully");

            String masqueradeContainer = wireguardContainerName + "-masquerade";
            try {
                log.info("Restarting masquerade sidecar: {}", masqueradeContainer);
                dockerClient.restartContainerCmd(masqueradeContainer)
                        .withTimeout(15)
                        .exec();
                Thread.sleep(3000);
                log.info("Masquerade sidecar restarted successfully");
            } catch (Exception e) {
                log.warn("Could not restart masquerade sidecar {}: {}", masqueradeContainer, e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while restarting WireGuard container", e);
            throw new RuntimeException("Failed to restart WireGuard service", e);
        } catch (Exception e) {
            log.error("Error restarting WireGuard container: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to restart WireGuard service", e);
        }
    }

    private String extractValue(String configContent, String key) {
        for (String line : configContent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + " =") || trimmed.startsWith(key + "=")) {
                return trimmed.substring(trimmed.indexOf('=') + 1).trim();
            }
        }
        return "";
    }

    private String generateScript(String peerName, String vpnIp, String serverUrl, String serverPort,
                                  String wgConfig, String lanCidr) {
        var sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("set -euo pipefail\n");
        sb.append("\n");
        sb.append("# Vaier peer setup script for: ").append(peerName).append("\n");
        sb.append("# VPN IP: ").append(vpnIp).append("\n");
        sb.append("# Server: ").append(serverUrl).append(":").append(serverPort).append("\n");
        sb.append("\n");
        sb.append("PEER_NAME=\"").append(peerName).append("\"\n");
        sb.append("VPN_IP=\"").append(vpnIp).append("\"\n");
        sb.append("INSTALL_DIR=\"$HOME/vaier\"\n");
        sb.append("\n");
        sb.append("docker_compose_up() {\n");
        sb.append("  local RETRIES=5\n");
        sb.append("  for i in $(seq 1 $RETRIES); do\n");
        sb.append("    docker compose up -d && return 0\n");
        sb.append("    echo \"docker compose up failed (attempt $i/$RETRIES), retrying in 5s...\"\n");
        sb.append("    sleep 5\n");
        sb.append("  done\n");
        sb.append("  echo 'ERROR: docker compose up failed after $RETRIES attempts'; exit 1\n");
        sb.append("}\n");
        sb.append("\n");
        sb.append("echo \"=== Vaier Peer Setup: $PEER_NAME ===\"\n");
        sb.append("echo \"\"\n");
        sb.append("\n");
        sb.append("# --- Install Docker ---\n");
        sb.append("if ! command -v docker &> /dev/null; then\n");
        sb.append("    echo \"Installing Docker...\"\n");
        sb.append("    curl -fsSL https://get.docker.com | sudo sh\n");
        sb.append("    sudo usermod -aG docker \"$USER\"\n");
        sb.append("    echo \"Docker installed.\"\n");
        sb.append("else\n");
        sb.append("    echo \"Docker already installed.\"\n");
        sb.append("fi\n");
        sb.append("sudo systemctl enable docker || true\n");
        sb.append("\n");
        sb.append("# --- Stop any existing services ---\n");
        sb.append("if [ -f \"$INSTALL_DIR/docker-compose.yml\" ]; then\n");
        sb.append("  echo \"Stopping existing services...\"\n");
        sb.append("  cd \"$INSTALL_DIR\" && docker compose down 2>/dev/null || true\n");
        sb.append("fi\n");
        sb.append("\n");
        sb.append("# --- Create directory structure ---\n");
        sb.append("echo \"Setting up $INSTALL_DIR...\"\n");
        sb.append("mkdir -p \"$INSTALL_DIR/wireguard-client/config/wg_confs\"\n");
        sb.append("\n");
        sb.append("# --- Write .env file ---\n");
        sb.append("cat > \"$INSTALL_DIR/.env\" << ENV_FILE\n");
        sb.append("PEER_NAME=").append(peerName).append("\n");
        sb.append("VPN_IP=").append(vpnIp).append("\n");
        sb.append("SERVER_URL=").append(serverUrl).append("\n");
        sb.append("SERVER_PORT=").append(serverPort).append("\n");
        sb.append("TZ=Europe/Oslo\n");
        sb.append("ENV_FILE\n");
        sb.append("\n");
        sb.append("echo \"Created .env file\"\n");
        sb.append("\n");
        sb.append("# --- Write WireGuard config ---\n");
        sb.append("cat > \"$INSTALL_DIR/wireguard-client/config/wg_confs/wg0.conf\" << 'WG_CONF'\n");
        sb.append(wgConfig).append("\n");
        sb.append("WG_CONF\n");
        sb.append("\n");
        sb.append("# Force split tunneling: only route VPN subnet through the tunnel\n");
        sb.append("# This prevents SSH and other external traffic from breaking\n");
        sb.append("sed -i 's|AllowedIPs.*=.*0\\.0\\.0\\.0/0.*|AllowedIPs = ").append(vpnSubnet).append("|' \"$INSTALL_DIR/wireguard-client/config/wg_confs/wg0.conf\"\n");
        sb.append("\n");
        sb.append("echo \"Created WireGuard config (split tunneling enabled)\"\n");
        sb.append("\n");
        sb.append("# --- Set sysctl on host (cannot use container sysctls with host network mode) ---\n");
        sb.append("sudo sysctl -w net.ipv4.conf.all.src_valid_mark=1\n");
        sb.append("echo 'net.ipv4.conf.all.src_valid_mark=1' | sudo tee -a /etc/sysctl.d/99-wireguard.conf > /dev/null\n");

        if (lanCidr != null && !lanCidr.isBlank()) {
            String lan = lanCidr.trim();
            sb.append("\n");
            sb.append("# --- Relay peer: forward VPN traffic to LAN ").append(lan).append(" ---\n");
            sb.append("sudo sysctl -w net.ipv4.ip_forward=1\n");
            sb.append("grep -qxF 'net.ipv4.ip_forward=1' /etc/sysctl.d/99-wireguard.conf 2>/dev/null \\\n");
            sb.append("  || echo 'net.ipv4.ip_forward=1' | sudo tee -a /etc/sysctl.d/99-wireguard.conf > /dev/null\n");
            sb.append("sudo iptables -t nat -C POSTROUTING -s ").append(vpnSubnet).append(" -d ").append(lan)
                .append(" -j MASQUERADE 2>/dev/null \\\n");
            sb.append("  || sudo iptables -t nat -A POSTROUTING -s ").append(vpnSubnet).append(" -d ").append(lan)
                .append(" -j MASQUERADE\n");
            sb.append("sudo iptables -C FORWARD -s ").append(vpnSubnet).append(" -d ").append(lan)
                .append(" -j ACCEPT 2>/dev/null \\\n");
            sb.append("  || sudo iptables -A FORWARD -s ").append(vpnSubnet).append(" -d ").append(lan)
                .append(" -j ACCEPT\n");
            sb.append("sudo iptables -C FORWARD -s ").append(lan).append(" -d ").append(vpnSubnet)
                .append(" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null \\\n");
            sb.append("  || sudo iptables -A FORWARD -s ").append(lan).append(" -d ").append(vpnSubnet)
                .append(" -m state --state RELATED,ESTABLISHED -j ACCEPT\n");
        }

        sb.append("\n");
        sb.append("# --- Write docker-compose.yml ---\n");
        sb.append("cat > \"$INSTALL_DIR/docker-compose.yml\" << 'COMPOSE'\n");
        sb.append("services:\n");
        sb.append("  wireguard-client:\n");
        sb.append("    image: lscr.io/linuxserver/wireguard:latest\n");
        sb.append("    container_name: wireguard-client\n");
        sb.append("    cap_add:\n");
        sb.append("      - NET_ADMIN\n");
        sb.append("      - SYS_MODULE\n");
        sb.append("    environment:\n");
        sb.append("      - PUID=1000\n");
        sb.append("      - PGID=1000\n");
        sb.append("      - TZ=${TZ:-Europe/Oslo}\n");
        sb.append("    volumes:\n");
        sb.append("      - ./wireguard-client/config/wg_confs:/config/wg_confs\n");
        sb.append("      - /lib/modules:/lib/modules:ro\n");
        sb.append("    restart: unless-stopped\n");
        sb.append("    network_mode: host\n");
        sb.append("COMPOSE\n");
        sb.append("\n");
        sb.append("echo \"Created docker-compose.yml\"\n");
        sb.append("\n");
        sb.append("# --- Configure Docker remote API (bind to 0.0.0.0, firewall restricts to VPN) ---\n");
        sb.append("echo \"Configuring Docker daemon for remote access...\"\n");
        sb.append("if snap list docker &>/dev/null; then\n");
        sb.append("  echo \"Detected snap Docker — writing config to snap path\"\n");
        sb.append("  sudo tee /var/snap/docker/current/config/daemon.json > /dev/null << 'DAEMON_JSON'\n");
        sb.append("{\n");
        sb.append("    \"hosts\": [\"unix:///var/run/docker.sock\", \"tcp://0.0.0.0:2375\"]\n");
        sb.append("}\n");
        sb.append("DAEMON_JSON\n");
        sb.append("  echo \"Restarting snap Docker daemon...\"\n");
        sb.append("  sudo systemctl restart snap.docker.dockerd || true\n");
        sb.append("else\n");
        sb.append("  sudo mkdir -p /etc/docker\n");
        sb.append("  sudo tee /etc/docker/daemon.json > /dev/null << 'DAEMON_JSON'\n");
        sb.append("{\n");
        sb.append("    \"hosts\": [\"unix:///var/run/docker.sock\", \"tcp://0.0.0.0:2375\"]\n");
        sb.append("}\n");
        sb.append("DAEMON_JSON\n");
        sb.append("  # Override systemd to not pass -H flag (conflicts with daemon.json hosts)\n");
        sb.append("  sudo mkdir -p /etc/systemd/system/docker.service.d\n");
        sb.append("  sudo tee /etc/systemd/system/docker.service.d/override.conf > /dev/null << 'OVERRIDE'\n");
        sb.append("[Service]\n");
        sb.append("ExecStart=\n");
        sb.append("ExecStart=/usr/bin/dockerd\n");
        sb.append("OVERRIDE\n");
        sb.append("  echo \"Reloading Docker daemon...\"\n");
        sb.append("  sudo systemctl daemon-reload || true\n");
        sb.append("  sudo systemctl restart docker || sudo service docker restart || true\n");
        sb.append("fi\n");
        sb.append("WAIT=0; until docker info > /dev/null 2>&1; do\n");
        sb.append("  if [ $WAIT -ge 30 ]; then\n");
        sb.append("    echo 'ERROR: Docker failed to start. Status:'; sudo systemctl status docker --no-pager; exit 1\n");
        sb.append("  fi\n");
        sb.append("  sleep 1; WAIT=$((WAIT+1))\n");
        sb.append("done\n");
        sb.append("\n");
        sb.append("# --- Firewall: only allow Docker API from VPN subnet ---\n");
        sb.append("echo \"Configuring firewall to restrict Docker API to VPN network...\"\n");
        sb.append("sudo iptables -D INPUT -p tcp --dport 2375 -j DROP 2>/dev/null || true\n");
        sb.append("sudo iptables -D INPUT -p tcp --dport 2375 -s ").append(vpnSubnet).append(" -j ACCEPT 2>/dev/null || true\n");
        sb.append("sudo iptables -A INPUT -p tcp --dport 2375 -s ").append(vpnSubnet).append(" -j ACCEPT\n");
        sb.append("sudo iptables -A INPUT -p tcp --dport 2375 -j DROP\n");
        sb.append("\n");
        sb.append("# Persist iptables rules across reboots\n");
        sb.append("if command -v netfilter-persistent &> /dev/null; then\n");
        sb.append("    sudo netfilter-persistent save\n");
        sb.append("elif command -v iptables-save &> /dev/null; then\n");
        sb.append("    sudo sh -c 'iptables-save > /etc/iptables/rules.v4' 2>/dev/null || true\n");
        sb.append("fi\n");
        sb.append("\n");
        sb.append("# --- Start all services ---\n");
        sb.append("echo \"Starting all services...\"\n");
        sb.append("cd \"$INSTALL_DIR\"\n");
        sb.append("docker_compose_up\n");
        sb.append("\n");
        sb.append("echo \"Waiting for VPN tunnel to establish...\"\n");
        sb.append("sleep 5\n");
        sb.append("if ! ip addr show | grep -q \"$VPN_IP\"; then\n");
        sb.append("    echo \"WARNING: VPN IP $VPN_IP not yet visible. Waiting longer...\"\n");
        sb.append("    sleep 10\n");
        sb.append("fi\n");
        sb.append("\n");
        sb.append("# WireGuard runs in host network mode, so it survives Docker restart\n");
        sb.append("echo \"\"\n");
        sb.append("echo \"=== Setup complete ===\"\n");
        sb.append("echo \"  Install dir:  $INSTALL_DIR\"\n");
        sb.append("echo \"  VPN IP:       $VPN_IP\"\n");
        sb.append("echo \"  Docker API:   tcp://0.0.0.0:2375 (firewalled to VPN subnet ").append(vpnSubnet).append(")\"\n");
        sb.append("echo \"\"\n");
        sb.append("echo \"Verify VPN connection:\"\n");
        sb.append("echo \"  docker exec wireguard-client wg show\"\n");
        return sb.toString();
    }

    private static class DockerExecCallback extends com.github.dockerjava.api.async.ResultCallbackTemplate<DockerExecCallback, com.github.dockerjava.api.model.Frame> {
        private final ByteArrayOutputStream stdout;
        private final ByteArrayOutputStream stderr;

        public DockerExecCallback(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public void onNext(com.github.dockerjava.api.model.Frame frame) {
            try {
                switch (frame.getStreamType()) {
                    case STDOUT:
                    case RAW:
                        stdout.write(frame.getPayload());
                        break;
                    case STDERR:
                        stderr.write(frame.getPayload());
                        break;
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to process Docker exec output", e);
            }
        }
    }
}
