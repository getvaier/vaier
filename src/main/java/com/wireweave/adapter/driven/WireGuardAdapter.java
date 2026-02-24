package com.wireweave.adapter.driven;

import com.wireweave.domain.WireGuardPeer;
import com.wireweave.domain.port.ForGettingWireGuardInterfaces;
import com.wireweave.domain.port.ForGettingWireGuardPeers;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WireGuardAdapter implements ForGettingWireGuardPeers, ForGettingWireGuardInterfaces {

    @Value("${wireguard.container.name:wireguard}")
    private String wireguardContainerName;

    private ProcessBuilder createWgProcessBuilder(String... wgArgs) {
        String containerName = System.getenv("WIREGUARD_CONTAINER_NAME");
        if (containerName == null) {
            containerName = wireguardContainerName;
        }

        // Check if running on Windows (for local development)
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        List<String> command = new ArrayList<>();
        if (isWindows) {
            // Windows local development - run wg.exe directly
            command.add("wg");
            command.addAll(List.of(wgArgs));
        } else {
            // Docker environment - use docker exec
            command.add("docker");
            command.add("exec");
            command.add(containerName);
            command.add("wg");
            command.addAll(List.of(wgArgs));
        }

        return new ProcessBuilder(command);
    }

    @Override
    public List<String> getInterfaces() {
        try {
            ProcessBuilder processBuilder = createWgProcessBuilder("show", "interfaces");
            Process process = processBuilder.start();

            List<String> interfaces = new ArrayList<>();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            // Read stdout
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                    // Interface names are space-separated on a single line
                    String[] interfaceNames = line.trim().split("\\s+");
                    for (String interfaceName : interfaceNames) {
                        if (!interfaceName.isEmpty()) {
                            interfaces.add(interfaceName);
                        }
                    }
                }
            }

            // Read stderr
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("wg command failed with exit code: " + exitCode +
                    "\nSTDOUT: " + stdout.toString() +
                    "\nSTDERR: " + stderr.toString());
            }

            return interfaces;

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute wg command to list interfaces", e);
        }
    }

    @Override
    public List<WireGuardPeer> getPeers(String interfaceName) {
        try {
            ProcessBuilder processBuilder = createWgProcessBuilder("show", interfaceName, "dump");
            Process process = processBuilder.start();

            List<WireGuardPeer> peers = new ArrayList<>();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            // Read stdout
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean firstLine = true;

                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");

                    // Skip the first line (interface info)
                    if (firstLine) {
                        firstLine = false;
                        continue;
                    }

                    // Parse peer line
                    // Format: public-key\tpreshared-key\tendpoint\tallowed-ips\tlatest-handshake\ttransfer-rx\ttransfer-tx\tpersistent-keepalive
                    String[] parts = line.split("\t");
                    if (parts.length >= 7) {
                        // Split endpoint into IP and port
                        String endpoint = parts[2];
                        String endpointIp = "";
                        String endpointPort = "";

                        if (!endpoint.equals("(none)") && endpoint.contains(":")) {
                            int lastColonIndex = endpoint.lastIndexOf(":");
                            endpointIp = endpoint.substring(0, lastColonIndex);
                            endpointPort = endpoint.substring(lastColonIndex + 1);

                            // Handle IPv6 addresses enclosed in brackets
                            if (endpointIp.startsWith("[") && endpointIp.endsWith("]")) {
                                endpointIp = endpointIp.substring(1, endpointIp.length() - 1);
                            }
                        }

                        peers.add(new WireGuardPeer(
                            parts[0], // publicKey
                            parts[3], // allowedIps
                            endpointIp, // endpointIp
                            endpointPort, // endpointPort
                            parts[4], // latestHandshake
                            parts[5], // transferRx
                            parts[6]  // transferTx
                        ));
                    }
                }
            }

            // Read stderr
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("wg command failed with exit code: " + exitCode +
                    "\nSTDOUT: " + stdout.toString() +
                    "\nSTDERR: " + stderr.toString());
            }

            return peers;

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute wg command for interface: " + interfaceName, e);
        }
    }

    public static void main(String[] args) {
        WireGuardAdapter adapter = new WireGuardAdapter();

        List<String> interfaces = adapter.getInterfaces();

        if(interfaces.isEmpty()) {
            System.out.println("No WireGuard interfaces found.");
            return;
        }

        System.out.println("=== WireGuard Peer Query Tool ===");
        System.out.println("NOTE: This may require administrator/root privileges");
        System.out.println("      On Windows, run IntelliJ as Administrator\n");
        System.out.println("Querying WireGuard interface: " + interfaces.get(0) + "\n");
        System.out.println("Running command: wg show " + interfaces.get(0) + " dump\n");

        try {
            List<WireGuardPeer> peers = adapter.getPeers(interfaces.get(0));

            System.out.println("Found " + peers.size() + " peer(s):\n");

            for (int i = 0; i < peers.size(); i++) {
                WireGuardPeer peer = peers.get(i);
                System.out.println("Peer #" + (i + 1) + ":");
                System.out.println("  Public Key:      " + peer.publicKey());
                System.out.println("  Allowed IPs:     " + peer.allowedIps());
                System.out.println("  Endpoint IP:     " + peer.endpointIp());
                System.out.println("  Endpoint Port:   " + peer.endpointPort());
                System.out.println("  Latest Handshake: " + peer.latestHandshake());
                System.out.println("  Transfer RX:     " + peer.transferRx() + " bytes");
                System.out.println("  Transfer TX:     " + peer.transferTx() + " bytes");
                System.out.println();
            }

            if (peers.isEmpty()) {
                System.out.println("No peers configured on this interface.");
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
