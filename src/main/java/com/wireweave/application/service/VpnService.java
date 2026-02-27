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
            // Execute command in WireGuard container to add peer
            String[] command = {"/app/add-peer", peerName};

            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(wireguardContainerName)
                    .withCmd(command)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            try {
                dockerClient.execStartCmd(execCreateCmdResponse.getId())
                        .exec(new DockerExecCallback(stdout, stderr))
                        .awaitCompletion();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Docker exec interrupted", e);
            }

            String stderrStr = stderr.toString();
            if (!stderrStr.isEmpty()) {
                log.warn("Peer creation stderr: {}", stderrStr);
            }

            log.info("Peer creation output: {}", stdout.toString());

            // Read the generated peer configuration
            Path peerConfigPath = Paths.get(wireguardConfigPath, peerName, peerName + ".conf");
            if (!Files.exists(peerConfigPath)) {
                log.error("Peer config file not found at: {}", peerConfigPath);
                throw new RuntimeException("Peer config file not found after creation");
            }

            String configContent = Files.readString(peerConfigPath);

            // Parse the config to extract details
            String ipAddress = extractValue(configContent, "Address");
            String privateKey = extractValue(configContent, "PrivateKey");
            String publicKey = extractValue(configContent, "PublicKey");

            log.info("Peer created successfully: {} with IP {}", peerName, ipAddress);

            return new CreatedPeerUco(
                    peerName,
                    ipAddress,
                    publicKey,
                    privateKey,
                    configContent
            );

        } catch (IOException e) {
            log.error("Error creating peer", e);
            throw new RuntimeException("Failed to create peer: " + e.getMessage(), e);
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
