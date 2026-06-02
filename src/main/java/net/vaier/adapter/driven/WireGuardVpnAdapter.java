package net.vaier.adapter.driven;

import net.vaier.domain.AllowedIps;
import net.vaier.domain.VpnClient;
import net.vaier.domain.WireGuardPeerConfig;
import net.vaier.domain.port.ForDeletingVpnPeers;
import net.vaier.domain.port.ForExecutingInContainer;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForUpdatingServerAllowedIps;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class WireGuardVpnAdapter implements ForGettingVpnClients, ForDeletingVpnPeers, ForUpdatingServerAllowedIps {

    @Value("${wireguard.config.path:/wireguard/config}")
    private String wireguardConfigPath;

    @Value("${wireguard.container.name:wireguard}")
    private String wireguardContainerName;

    @Value("${wireguard.interface:wg0}")
    private String wireguardInterface;

    /** Windows is the local-dev path: {@code wg} runs as a native process, no Docker daemon. */
    private final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

    private final ForExecutingInContainer forExecutingInContainer;

    public WireGuardVpnAdapter(ForExecutingInContainer forExecutingInContainer) {
        this.forExecutingInContainer = forExecutingInContainer;
    }

    private String executeWgCommand(String... wgArgs) throws IOException, InterruptedException {
        if (isWindows) {
            // Windows local development - run wg.exe directly
            return executeLocalCommand(wgArgs);
        } else {
            // Docker environment - use Docker API
            return executeDockerExec(wgArgs);
        }
    }

    private String executeLocalCommand(String... wgArgs) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("wg");
        command.addAll(List.of(wgArgs));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line).append("\n");
            }
        }

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

        return stdout.toString();
    }

    private String executeDockerExec(String... wgArgs) {
        String containerName = System.getenv("WIREGUARD_CONTAINER_NAME");
        if (containerName == null) {
            containerName = wireguardContainerName;
        }

        String[] command = new String[wgArgs.length + 1];
        command[0] = "wg";
        System.arraycopy(wgArgs, 0, command, 1, wgArgs.length);

        return forExecutingInContainer.execute(containerName, command);
    }

    record PeerInfo(String publicKey, String allowedIps) {}

    private String findPeerPublicKeyByIp(String interfaceName, String ipAddress) throws IOException, InterruptedException {
        PeerInfo info = findPeerInfoByIp(interfaceName, ipAddress);
        return info == null ? "" : info.publicKey();
    }

    private PeerInfo findPeerInfoByIp(String interfaceName, String ipAddress) throws IOException, InterruptedException {
        String output = forExecutingInContainer.execute(wireguardContainerName, "wg", "show", interfaceName, "dump");

        try (var reader = new java.io.BufferedReader(new java.io.StringReader(output))) {
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue;
                }

                String[] parts = line.split("\t");
                if (parts.length >= 4) {
                    String publicKey = parts[0];
                    String allowedIps = parts[3];

                    for (String cidr : allowedIps.split(",")) {
                        String trimmed = cidr.trim();
                        if (trimmed.startsWith(ipAddress + "/") || trimmed.equals(ipAddress)) {
                            log.info("Found peer with public key {} for IP {}", publicKey, ipAddress);
                            return new PeerInfo(publicKey, allowedIps);
                        }
                    }
                }
            }
        }

        log.warn("No peer found with IP address: {}", ipAddress);
        return null;
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            Files.walk(directory)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path, e);
                        }
                    });
        }
    }

    @Override
    public void setPeerAllowedIps(String peerIpAddress, String allowedIps) {
        log.info("Updating server-side AllowedIPs for peer at {} to: {}", peerIpAddress, allowedIps);
        try {
            PeerInfo peerInfo = findPeerInfoByIp(wireguardInterface, peerIpAddress);
            if (peerInfo == null) {
                throw new RuntimeException("No peer found on " + wireguardInterface + " with VPN IP " + peerIpAddress);
            }

            String oldAllowedIps = peerInfo.allowedIps();
            AllowedIps.RouteDelta routeDelta = AllowedIps.routeDelta(oldAllowedIps, allowedIps);

            // Argv-style — no shell, so user-supplied lanCidr cannot break out of `allowed-ips`.
            // Closes #195.
            forExecutingInContainer.execute(wireguardContainerName, "wg", "set", wireguardInterface,
                "peer", peerInfo.publicKey(), "allowed-ips", allowedIps);

            // Persist live runtime state to wg0.conf so it survives a container restart.
            forExecutingInContainer.execute(wireguardContainerName, "wg-quick", "save", wireguardInterface);
            log.info("Persisted new AllowedIPs for peer at {}", peerIpAddress);

            // wg set / wg-quick save mutate cryptokey routing but never install kernel
            // routes — those are only added by wg-quick up at bring-up. Reconcile them
            // here so the kernel FIB matches the new AllowedIPs without restarting wg0.
            reconcileKernelRoutes(routeDelta);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to update AllowedIPs for peer at " + peerIpAddress + ": " + e.getMessage(), e);
        }
    }

    private void reconcileKernelRoutes(AllowedIps.RouteDelta routeDelta) throws IOException, InterruptedException {
        for (String cidr : routeDelta.toAdd()) {
            log.info("Installing kernel route: {} dev {}", cidr, wireguardInterface);
            forExecutingInContainer.execute(wireguardContainerName, "ip", "route", "replace", cidr, "dev", wireguardInterface);
        }
        for (String cidr : routeDelta.toRemove()) {
            log.info("Removing kernel route: {} dev {}", cidr, wireguardInterface);
            // Argv-style. The exec port already discards non-zero exits silently
            // (the previous implementation used `2>/dev/null || true` in a shell wrapper
            // because the previous executor surfaced exit codes); we drop the shell so
            // user-supplied cidr cannot inject. Closes #195.
            forExecutingInContainer.execute(wireguardContainerName, "ip", "route", "del", cidr, "dev", wireguardInterface);
        }
    }

    @Override
    public void deletePeer(String peerName) {
        log.info("Deleting peer {} from interface {}", peerName, wireguardInterface);

        try {
            Path peerConfigPath = Paths.get(wireguardConfigPath, peerName, peerName + ".conf");
            if (!Files.exists(peerConfigPath)) {
                log.warn("Peer config not found: {}", peerConfigPath);
                throw new RuntimeException("Peer not found: " + peerName);
            }

            String configContent = Files.readString(peerConfigPath);
            String ipAddress = WireGuardPeerConfig.readIpAddress(configContent);

            if (ipAddress.isEmpty()) {
                log.error("Could not find IP address in peer config");
                throw new RuntimeException("Invalid peer configuration");
            }

            log.info("Found peer IP address: {}", ipAddress);

            String publicKey = findPeerPublicKeyByIp(wireguardInterface, ipAddress);
            if (publicKey.isEmpty()) {
                log.error("Could not find peer with IP {} in WireGuard interface", ipAddress);
                throw new RuntimeException("Peer not found in WireGuard interface");
            }

            log.info("Removing peer with public key: {}", publicKey);

            // Argv-style — no shell. publicKey is computed locally from `wg show`,
            // not user-supplied, but the sh-c pattern is being purged repo-wide. Closes #195.
            String output = forExecutingInContainer.execute(
                wireguardContainerName, "wg", "set", wireguardInterface, "peer", publicKey, "remove");
            log.info("Remove peer output: {}", output);

            String saveOutput = forExecutingInContainer.execute(
                wireguardContainerName, "wg-quick", "save", wireguardInterface);
            log.info("Save config output: {}", saveOutput);

            deleteDirectory(Paths.get(wireguardConfigPath, peerName));
            log.info("Deleted peer directory: {}", peerName);

            log.info("Peer deleted successfully: {}", peerName);

        } catch (IOException | InterruptedException e) {
            log.error("Error deleting peer", e);
            throw new RuntimeException("Failed to delete peer: " + e.getMessage(), e);
        }
    }

    private List<String> getInterfaces() {
        try {
            String output = executeWgCommand("show", "interfaces");

            List<String> interfaces = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new StringReader(output))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Interface names are space-separated on a single line
                    String[] interfaceNames = line.trim().split("\\s+");
                    for (String interfaceName : interfaceNames) {
                        if (!interfaceName.isEmpty()) {
                            interfaces.add(interfaceName);
                        }
                    }
                }
            }

            return interfaces;

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute wg command to list interfaces", e);
        }
    }

    @Override
    public List<VpnClient> getClients() {
        return getInterfaces().stream()
            .flatMap(interfaceName -> getClients(interfaceName).stream())
            .toList();
    }

    private List<VpnClient> getClients(String interfaceName) {
        try {
            String output = executeWgCommand("show", interfaceName, "dump");

            List<VpnClient> clients = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new StringReader(output))) {
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

                        clients.add(new VpnClient(
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

            return clients;

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute wg command for interface: " + interfaceName, e);
        }
    }

}
