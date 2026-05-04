package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForDeletingVpnPeers;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForUpdatingServerAllowedIps;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    private DockerClient dockerClient;
    private boolean isWindows;

    @PostConstruct
    public void init() {
        isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        if (!isWindows) {
            try {
                String dockerHost = resolveDockerHost();
                log.info("Docker host configured as: {}", dockerHost);

                if (dockerHost.startsWith("unix://")) {
                    java.nio.file.Path socketPath = java.nio.file.Paths.get(dockerHost.substring("unix://".length()));
                    if (!java.nio.file.Files.exists(socketPath)) {
                        log.error("Docker socket not found at {}", socketPath);
                        throw new RuntimeException("Docker socket not accessible");
                    }
                }

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
                log.info("Docker client initialized for WireGuard container access");

                dockerClient.pingCmd().exec();
                log.info("Successfully connected to Docker daemon");
            } catch (Exception e) {
                log.error("Failed to initialize Docker client: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to connect to Docker daemon", e);
            }
        }
    }

    private static String resolveDockerHost() {
        String env = System.getenv("DOCKER_HOST");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return "unix:///var/run/docker.sock";
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

    private String executeDockerExec(String... wgArgs) throws IOException {
        String containerName = System.getenv("WIREGUARD_CONTAINER_NAME");
        if (containerName == null) {
            containerName = wireguardContainerName;
        }

        List<String> command = new ArrayList<>();
        command.add("wg");
        command.addAll(List.of(wgArgs));

        ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerName)
            .withCmd(command.toArray(new String[0]))
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
            log.warn("WireGuard command stderr: {}", stderrStr);
        }

        return stdout.toString();
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

    record PeerInfo(String publicKey, String allowedIps) {}

    private String findPeerPublicKeyByIp(String interfaceName, String ipAddress) throws IOException, InterruptedException {
        PeerInfo info = findPeerInfoByIp(interfaceName, ipAddress);
        return info == null ? "" : info.publicKey();
    }

    private PeerInfo findPeerInfoByIp(String interfaceName, String ipAddress) throws IOException, InterruptedException {
        String output = executeInContainer("wg", "show", interfaceName, "dump");

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

    static String extractValue(String configContent, String key) {
        for (String line : configContent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + " =") || trimmed.startsWith(key + "=")) {
                return trimmed.substring(trimmed.indexOf('=') + 1).trim();
            }
        }
        return "";
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
            RouteDelta routeDelta = computeRouteDelta(oldAllowedIps, allowedIps);

            String setCommand = String.format("wg set %s peer %s allowed-ips %s",
                wireguardInterface, peerInfo.publicKey(), allowedIps);
            log.debug("Executing: {}", setCommand);
            executeInContainer("sh", "-c", setCommand);

            // Persist live runtime state to wg0.conf so it survives a container restart.
            executeInContainer("wg-quick", "save", wireguardInterface);
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

    private void reconcileKernelRoutes(RouteDelta routeDelta) throws IOException, InterruptedException {
        for (String cidr : routeDelta.toAdd()) {
            log.info("Installing kernel route: {} dev {}", cidr, wireguardInterface);
            executeInContainer("ip", "route", "replace", cidr, "dev", wireguardInterface);
        }
        for (String cidr : routeDelta.toRemove()) {
            log.info("Removing kernel route: {} dev {}", cidr, wireguardInterface);
            // best-effort: ignore "No such process" / non-zero exits via sh wrapper
            executeInContainer("sh", "-c",
                String.format("ip route del %s dev %s 2>/dev/null || true", cidr, wireguardInterface));
        }
    }

    record RouteDelta(List<String> toAdd, List<String> toRemove) {}

    /**
     * Compute which CIDRs need {@code ip route replace} (add) or {@code ip route del} (remove)
     * inside the wireguard container, given the previous and new {@code AllowedIPs} for one peer.
     *
     * <p>{@code /32} host routes are skipped — they're managed by {@code wg-quick up} and we must
     * not touch them here. Non-/32 CIDRs in the new set are always emitted as adds (idempotent
     * via {@code ip route replace}) so drift from earlier {@code wg set} calls is healed.
     */
    static RouteDelta computeRouteDelta(String oldAllowedIps, String newAllowedIps) {
        List<String> oldCidrs = parseCidrList(oldAllowedIps);
        List<String> newCidrs = parseCidrList(newAllowedIps);
        Set<String> newSet = new HashSet<>(newCidrs);

        List<String> toAdd = newCidrs.stream()
                .filter(cidr -> !cidr.endsWith("/32"))
                .distinct()
                .toList();

        List<String> toRemove = oldCidrs.stream()
                .filter(cidr -> !cidr.endsWith("/32"))
                .filter(cidr -> !newSet.contains(cidr))
                .distinct()
                .toList();

        return new RouteDelta(toAdd, toRemove);
    }

    private static List<String> parseCidrList(String allowedIps) {
        if (allowedIps == null || allowedIps.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String cidr : allowedIps.split(",")) {
            String trimmed = cidr.trim();
            if (!trimmed.isEmpty()) {
                seen.add(trimmed);
            }
        }
        return new ArrayList<>(seen);
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
            String ipAddress = "";
            for (String line : configContent.split("\n")) {
                if (line.trim().startsWith("Address")) {
                    String address = line.substring(line.indexOf('=') + 1).trim();
                    ipAddress = address.split("/")[0];
                    break;
                }
            }

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

            String removePeerCommand = String.format("wg set %s peer %s remove", wireguardInterface, publicKey);
            log.info("Executing: {}", removePeerCommand);
            String output = executeInContainer("sh", "-c", removePeerCommand);
            log.info("Remove peer output: {}", output);

            String saveOutput = executeInContainer("wg-quick", "save", wireguardInterface);
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
