package com.wireweave.application.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.wireweave.application.CreatePeerUseCase;
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
public class VpnService implements CreatePeerUseCase {

    @Value("${wireguard.config.path:/wireguard/config}")
    private String wireguardConfigPath;

    @Value("${wireguard.container.name:wireguard}")
    private String wireguardContainerName;

    private DockerClient dockerClient;

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing Docker client for VpnService");

            DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost("unix:///var/run/docker.sock")
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
        return createPeer(interfaceName, peerName, true);
    }

    @Override
    public CreatedPeerUco createPeer(String interfaceName, String peerName, boolean routeAllTraffic) {
        log.info("Creating peer {} on interface {} (routeAllTraffic: {})", peerName, interfaceName, routeAllTraffic);

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

            // Step 5: Read server configuration to get server public key
            Path serverConfigPath = Paths.get(wireguardConfigPath, interfaceName + ".conf");
            String serverConfig = Files.readString(serverConfigPath);
            String serverPublicKey = extractValue(serverConfig, "PublicKey");
            String serverEndpoint = extractServerEndpoint();
            String allowedIps = routeAllTraffic ? "0.0.0.0/0" : "10.13.13.0/24";

            // Step 6: Create peer directory
            Path peerDir = Paths.get(wireguardConfigPath, peerName);
            Files.createDirectories(peerDir);

            // Step 7: Create client configuration file
            String clientConfig = generateClientConfig(
                    privateKey,
                    ipAddress,
                    serverPublicKey,
                    presharedKey,
                    serverEndpoint,
                    allowedIps
            );

            Path peerConfigPath = peerDir.resolve(peerName + ".conf");
            Files.writeString(peerConfigPath, clientConfig);
            log.info("Created client config file at {}", peerConfigPath);

            // Step 8: Add peer to server configuration
            addPeerToServer(interfaceName, publicKey, presharedKey, ipAddress);
            log.info("Added peer to server configuration");

            log.info("Peer created successfully: {} with IP {}", peerName, ipAddress);

            return new CreatedPeerUco(
                    peerName,
                    ipAddress,
                    publicKey,
                    clientConfig
            );

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

    private String extractServerEndpoint() {
        return System.getenv().getOrDefault("SERVERURL", "wireweave.eilertsen.family") + ":" +
               System.getenv().getOrDefault("SERVERPORT", "51820");
    }

    private String generateClientConfig(String privateKey, String ipAddress, String serverPublicKey,
                                        String presharedKey, String serverEndpoint, String allowedIps) {
        return String.format("""
                [Interface]
                PrivateKey = %s
                Address = %s/32
                DNS = 10.13.13.1

                [Peer]
                PublicKey = %s
                PresharedKey = %s
                Endpoint = %s
                AllowedIPs = %s
                PersistentKeepalive = 25
                """, privateKey, ipAddress, serverPublicKey, presharedKey, serverEndpoint, allowedIps);
    }

    private void addPeerToServer(String interfaceName, String publicKey, String presharedKey, String ipAddress)
            throws IOException, InterruptedException {
        // Create a temporary file for the preshared key
        String pskFile = "/tmp/psk_" + System.currentTimeMillis();
        executeInContainer("sh", "-c", "echo '" + presharedKey + "' > " + pskFile);

        // Add peer to running WireGuard interface
        String addPeerCommand = String.format(
                "wg set %s peer %s preshared-key %s allowed-ips %s/32",
                interfaceName, publicKey, pskFile, ipAddress
        );
        log.info("Executing: {}", addPeerCommand);
        String output = executeInContainer("sh", "-c", addPeerCommand);
        log.info("Add peer output: {}", output);

        // Clean up temp file
        executeInContainer("rm", "-f", pskFile);

        // Save configuration to make it persistent
        String saveOutput = executeInContainer("wg-quick", "save", interfaceName);
        log.info("Save config output: {}", saveOutput);
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
