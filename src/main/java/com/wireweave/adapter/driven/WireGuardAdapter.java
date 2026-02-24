package com.wireweave.adapter.driven;

import com.wireweave.domain.WireGuardPeer;
import com.wireweave.domain.port.ForGettingWireGuardPeers;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class WireGuardAdapter implements ForGettingWireGuardPeers {

    @Override
    public List<WireGuardPeer> getPeers(String interfaceName) {
        try {
            // Use full path to wg.exe on Windows
            String wgCommand = System.getProperty("os.name").toLowerCase().contains("win")
                ? "C:\\Program Files\\WireGuard\\wg.exe"
                : "wg";

            ProcessBuilder processBuilder = new ProcessBuilder(wgCommand, "show", interfaceName, "dump");
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

        // Use first argument as interface name, or default to "wg0"
        String interfaceName = args.length > 0 ? args[0] : "Frankfurt";

        System.out.println("=== WireGuard Peer Query Tool ===");
        System.out.println("NOTE: This may require administrator/root privileges");
        System.out.println("      On Windows, run IntelliJ as Administrator\n");
        System.out.println("Querying WireGuard interface: " + interfaceName);
        System.out.println("Running command: wg show " + interfaceName + " dump\n");

        try {
            List<WireGuardPeer> peers = adapter.getPeers(interfaceName);

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
