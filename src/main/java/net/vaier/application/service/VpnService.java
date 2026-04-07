package net.vaier.application.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import net.vaier.application.CreatePeerUseCase;
import net.vaier.config.ServiceNames;
import net.vaier.domain.PeerType;
import net.vaier.domain.port.ForDeletingVpnPeers;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForRestoringVpnPeers;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class VpnService implements CreatePeerUseCase, ForDeletingVpnPeers, ForRestoringVpnPeers {

    @Value("${wireguard.config.path:/wireguard/config}")
    private String wireguardConfigPath;

    @Value("${wireguard.container.name:wireguard}")
    private String wireguardContainerName;

    private DockerClient dockerClient;

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing Docker client for VpnService");

            // Detect platform and use appropriate Docker host
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
        // Check environment variable first
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null && !dockerHost.isEmpty()) {
            return dockerHost;
        }

        // Detect platform
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            // Windows uses named pipes
            return "npipe:////./pipe/docker_engine";
        } else if (os.contains("mac")) {
            // macOS can use Unix socket
            return "unix:///var/run/docker.sock";
        } else {
            // Linux uses Unix socket
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

    @Override
    public CreatedPeerUco createPeer(String interfaceName, String peerName) {
        return createPeer(interfaceName, peerName, PeerType.UBUNTU_SERVER, null);
    }

    @Override
    public CreatedPeerUco createPeer(String interfaceName, String peerName, PeerType peerType, String lanCidr) {
        peerName = peerName.trim().replaceAll("[^a-zA-Z0-9_-]", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        log.info("Creating peer {} on interface {} (peerType: {}, lanCidr: {})", peerName, interfaceName, peerType, lanCidr);

        try {
            // Step 1: Generate private key
            String privateKey = executeInContainer("wg", "genkey").trim();
            log.info("Generated private key for peer {}", peerName);

            // Step 2: Generate public key from private key
            String publicKey = executeInContainerWithInput(privateKey, "wg", "pubkey").trim();
            log.info("Generated public key for peer {}: {}", peerName, publicKey);

            // Step 3: Generate preshared key
            String presharedKey = executeInContainer("wg", "genpsk").trim();
            log.info("Generated preshared key for peer {}", peerName);

            // Step 4: Find the next available IP address
            String ipAddress = findNextAvailableIp();
            log.info("Assigned IP address {} to peer {}", ipAddress, peerName);

            // Step 5: Get server public key
            String serverPublicKey = getServerPublicKey(interfaceName);
            String serverEndpoint = extractServerEndpoint();

            // Step 6: Create peer directory
            Path peerDir = Paths.get(wireguardConfigPath, peerName);
            Files.createDirectories(peerDir);

            // Step 7: Create client configuration file
            String clientConfig = generateClientConfig(
                    privateKey, ipAddress, serverPublicKey, presharedKey, serverEndpoint, peerType, lanCidr);

            Path peerConfigPath = peerDir.resolve(peerName + ".conf");
            Files.writeString(peerConfigPath, clientConfig);
            log.info("Created client config file at {}", peerConfigPath);

            // Step 8: Add peer to server configuration (include LAN CIDR for server-type peers)
            addPeerToServer(interfaceName, publicKey, presharedKey, ipAddress, lanCidr);
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
        // Use bash to pipe input to command
        String bashCommand = String.format("echo '%s' | %s", input, String.join(" ", command));
        return executeInContainer("bash", "-c", bashCommand);
    }

    private String findNextAvailableIp() throws IOException {
        Path configPath = Paths.get(wireguardConfigPath);
        AtomicInteger maxLastOctet = new AtomicInteger(1);

        // Find all existing peer IPs by scanning peer directories
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

        // Return next available IP (start from .2, server is .1)
        int nextOctet = Math.max(maxLastOctet.get() + 1, 2);
        return "10.13.13." + nextOctet;
    }

    private String getServerPublicKey(String interfaceName) throws IOException, InterruptedException {
        // Get the server's public key from the running WireGuard interface
        // Note: the config file only contains PrivateKey for the server, and PublicKey entries
        // belong to peers - so we must derive the server's public key from the running interface.
        log.info("Getting server public key from running interface {}", interfaceName);
        String output = executeInContainer("wg", "show", interfaceName, "public-key");
        String publicKey = output.trim();
        log.info("Got server public key from interface: {}", publicKey);
        return publicKey;
    }

    private String extractServerEndpoint() {
        String vaierDomain = System.getenv("VAIER_DOMAIN");
        String serverUrl;

        if (vaierDomain != null && !vaierDomain.isEmpty()) {
            serverUrl = "vaier." + vaierDomain;
        } else {
            // Fallback to SERVERURL if VAIER_DOMAIN is not set
            serverUrl = System.getenv().getOrDefault("SERVERURL", "vaier.eilertsen.family");
        }

        String serverPort = System.getenv().getOrDefault("SERVERPORT", ServiceNames.DEFAULT_WG_PORT);
        return serverUrl + ":" + serverPort;
    }

    static String generateClientConfig(String privateKey, String ipAddress, String serverPublicKey,
                                       String presharedKey, String serverEndpoint,
                                       PeerType peerType, String lanCidr) {
        String allowedIps = peerType.defaultAllowedIps();
        if (lanCidr != null && !lanCidr.isBlank() && peerType == PeerType.UBUNTU_SERVER) {
            allowedIps = allowedIps + ", " + lanCidr;
        }

        String vaierJson = lanCidr != null && !lanCidr.isBlank() && peerType == PeerType.UBUNTU_SERVER
                ? String.format("{\"peerType\":\"%s\",\"lanCidr\":\"%s\"}", peerType.name(), lanCidr)
                : String.format("{\"peerType\":\"%s\"}", peerType.name());

        String dnsLine = peerType.isServerType()
                ? ""
                : "DNS = 10.13.13.1\n";

        return String.format("""
                # VAIER: %s
                [Interface]
                PrivateKey = %s
                Address = %s/32
                %s
                [Peer]
                PublicKey = %s
                PresharedKey = %s
                Endpoint = %s
                AllowedIPs = %s
                PersistentKeepalive = 25
                """, vaierJson, privateKey, ipAddress, dnsLine,
                serverPublicKey, presharedKey, serverEndpoint, allowedIps);
    }

    private void addPeerToServer(String interfaceName, String publicKey, String presharedKey,
                                 String ipAddress, String lanCidr)
            throws IOException, InterruptedException {
        // Create a temporary file for the preshared key
        String pskFile = "/tmp/psk_" + System.currentTimeMillis();
        executeInContainer("sh", "-c", "echo '" + presharedKey + "' > " + pskFile);

        String serverAllowedIps = ipAddress + "/32";
        if (lanCidr != null && !lanCidr.isBlank()) {
            serverAllowedIps = serverAllowedIps + "," + lanCidr;
        }

        // Add peer to running WireGuard interface
        String addPeerCommand = String.format(
                "wg set %s peer %s preshared-key %s allowed-ips %s",
                interfaceName, publicKey, pskFile, serverAllowedIps
        );
        log.info("Executing: {}", addPeerCommand);
        String output = executeInContainer("sh", "-c", addPeerCommand);
        log.info("Add peer output: {}", output);

        // Clean up temp file
        executeInContainer("rm", "-f", pskFile);

        // Save configuration to make it persistent
        String saveOutput = executeInContainer("wg-quick", "save", interfaceName);
        log.info("Save config output: {}", saveOutput);

        // Restart WireGuard service to ensure NAT rules are effective
        restartWireGuardService();
        log.info("WireGuard service restarted to apply NAT rules");
    }

    private void restartWireGuardService() {
        try {
            log.info("Restarting WireGuard container: {}", wireguardContainerName);
            dockerClient.restartContainerCmd(wireguardContainerName)
                    .withTimeout(30)
                    .exec();

            // Wait a moment for the container to fully restart
            Thread.sleep(2000);

            log.info("WireGuard container restarted successfully");

            // Restart the masquerade sidecar so it re-attaches to WireGuard's new
            // network namespace and re-installs the POSTROUTING MASQUERADE rule.
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

    private String findPeerPublicKeyByIp(String interfaceName, String ipAddress) throws IOException, InterruptedException {
        // Query WireGuard to get all peers and find the one with matching IP
        String output = executeInContainer("wg", "show", interfaceName, "dump");

        try (var reader = new java.io.BufferedReader(new java.io.StringReader(output))) {
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                // Skip the first line (interface info)
                if (firstLine) {
                    firstLine = false;
                    continue;
                }

                // Parse peer line
                // Format: public-key\tpreshared-key\tendpoint\tallowed-ips\tlatest-handshake\ttransfer-rx\ttransfer-tx\tpersistent-keepalive
                String[] parts = line.split("\t");
                if (parts.length >= 4) {
                    String publicKey = parts[0];
                    String allowedIps = parts[3];

                    // Check if this peer has the matching IP
                    if (allowedIps.startsWith(ipAddress + "/") || allowedIps.equals(ipAddress)) {
                        log.info("Found peer with public key {} for IP {}", publicKey, ipAddress);
                        return publicKey;
                    }
                }
            }
        }

        log.warn("No peer found with IP address: {}", ipAddress);
        return "";
    }

    @Override
    public void restorePeer(String interfaceName, ForGettingPeerConfigurations.PeerConfiguration config) {
        String peerName = config.name();
        String configContent = config.configContent();
        String ipAddress = config.ipAddress();
        String lanCidr = config.lanCidr();

        log.info("Restoring peer {} on interface {}", peerName, interfaceName);

        try {
            String privateKey = extractValue(configContent, "PrivateKey");
            String presharedKey = extractValue(configContent, "PresharedKey");

            if (privateKey.isEmpty()) {
                throw new RuntimeException("Cannot restore peer " + peerName + ": PrivateKey not found in config");
            }

            String publicKey = executeInContainerWithInput(privateKey, "wg", "pubkey").trim();
            log.info("Derived public key for peer {}: {}", peerName, publicKey);

            Path peerDir = Paths.get(wireguardConfigPath, peerName);
            Files.createDirectories(peerDir);
            Files.writeString(peerDir.resolve(peerName + ".conf"), configContent);
            log.info("Wrote config file for peer {}", peerName);

            String serverAllowedIps = ipAddress + "/32";
            if (lanCidr != null && !lanCidr.isBlank()) {
                serverAllowedIps = serverAllowedIps + "," + lanCidr;
            }

            if (!presharedKey.isEmpty()) {
                String pskFile = "/tmp/psk_restore_" + System.currentTimeMillis();
                executeInContainer("sh", "-c", "echo '" + presharedKey + "' > " + pskFile);
                executeInContainer("sh", "-c", String.format(
                        "wg set %s peer %s preshared-key %s allowed-ips %s",
                        interfaceName, publicKey, pskFile, serverAllowedIps));
                executeInContainer("rm", "-f", pskFile);
            } else {
                executeInContainer("sh", "-c", String.format(
                        "wg set %s peer %s allowed-ips %s",
                        interfaceName, publicKey, serverAllowedIps));
            }

            executeInContainer("wg-quick", "save", interfaceName);
            restartWireGuardService();

            log.info("Peer {} restored successfully", peerName);

        } catch (IOException | InterruptedException e) {
            log.error("Error restoring peer {}", peerName, e);
            throw new RuntimeException("Failed to restore peer: " + e.getMessage(), e);
        }
    }

    @Override
    public void deletePeer(String interfaceName, String peerName) {
        log.info("Deleting peer {} from interface {}", peerName, interfaceName);

        try {
            // Step 1: Read the peer config to get the IP address
            Path peerConfigPath = Paths.get(wireguardConfigPath, peerName, peerName + ".conf");
            if (!Files.exists(peerConfigPath)) {
                log.warn("Peer config not found: {}", peerConfigPath);
                throw new RuntimeException("Peer not found: " + peerName);
            }

            String configContent = Files.readString(peerConfigPath);
            String ipAddress = "";
            for (String line : configContent.split("\n")) {
                if (line.trim().startsWith("Address")) {
                    String address = line.substring(line.indexOf('=') + 1).trim();
                    ipAddress = address.split("/")[0]; // Extract IP without CIDR
                    break;
                }
            }

            if (ipAddress.isEmpty()) {
                log.error("Could not find IP address in peer config");
                throw new RuntimeException("Invalid peer configuration");
            }

            log.info("Found peer IP address: {}", ipAddress);

            // Step 2: Find the peer's public key by querying WireGuard for peers with this IP
            String publicKey = findPeerPublicKeyByIp(interfaceName, ipAddress);
            if (publicKey.isEmpty()) {
                log.error("Could not find peer with IP {} in WireGuard interface", ipAddress);
                throw new RuntimeException("Peer not found in WireGuard interface");
            }

            log.info("Removing peer with public key: {}", publicKey);

            // Step 3: Remove peer from running WireGuard interface
            String removePeerCommand = String.format("wg set %s peer %s remove", interfaceName, publicKey);
            log.info("Executing: {}", removePeerCommand);
            String output = executeInContainer("sh", "-c", removePeerCommand);
            log.info("Remove peer output: {}", output);

            // Step 4: Save configuration to make it persistent
            String saveOutput = executeInContainer("wg-quick", "save", interfaceName);
            log.info("Save config output: {}", saveOutput);

            // Step 5: Delete peer directory and config files
            deleteDirectory(Paths.get(wireguardConfigPath, peerName));
            log.info("Deleted peer directory: {}", peerName);

            log.info("Peer deleted successfully: {}", peerName);

        } catch (IOException | InterruptedException e) {
            log.error("Error deleting peer", e);
            throw new RuntimeException("Failed to delete peer: " + e.getMessage(), e);
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            Files.walk(directory)
                    .sorted((a, b) -> -a.compareTo(b)) // Reverse order to delete files before directories
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path, e);
                        }
                    });
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
