package com.wireweave.application.service;

import com.wireweave.application.CreatePeerUseCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class VpnService implements CreatePeerUseCase {

    @Value("${wireguard.config.path:/wireguard/config}")
    private String wireguardConfigPath;

    @Value("${wireguard.container.name:wireguard}")
    private String wireguardContainerName;

    @Override
    public CreatedPeerUco createPeer(String interfaceName, String peerName) {
        return createPeer(interfaceName, peerName, true);
    }

    @Override
    public CreatedPeerUco createPeer(String interfaceName, String peerName, boolean routeAllTraffic) {
        log.info("Creating peer {} on interface {} (routeAllTraffic: {})", peerName, interfaceName, routeAllTraffic);

        try {
            // Execute docker exec to add peer inside the WireGuard container
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.add("exec");
            command.add(wireguardContainerName);
            command.add("/app/add-peer");
            command.add(peerName);

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                while ((line = errorReader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("Failed to create peer. Exit code: {}, Error: {}", exitCode, errorOutput);
                throw new RuntimeException("Failed to create peer: " + errorOutput);
            }

            log.info("Peer creation output: {}", output);

            // Read the generated peer configuration
            Path peerConfigPath = Paths.get(wireguardConfigPath, peerName, peerName + ".conf");
            String configContent = Files.readString(peerConfigPath);

            // Parse the config to extract details
            String ipAddress = extractValue(configContent, "Address");
            String privateKey = extractValue(configContent, "PrivateKey");
            String publicKey = extractValue(configContent, "PublicKey");

            return new CreatedPeerUco(
                    peerName,
                    ipAddress,
                    publicKey,
                    privateKey,
                    configContent
            );

        } catch (IOException | InterruptedException e) {
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
}
