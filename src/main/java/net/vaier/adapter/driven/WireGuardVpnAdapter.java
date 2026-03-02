package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingVpnClients;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class WireGuardVpnAdapter implements ForGettingVpnClients {

    @Value("${wireguard.container.name:wireguard}")
    private String wireguardContainerName;

    private DockerClient dockerClient;
    private boolean isWindows;

    @PostConstruct
    public void init() {
        isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        if (!isWindows) {
            try {
                // Check if Docker socket exists
                java.nio.file.Path socketPath = java.nio.file.Paths.get("/var/run/docker.sock");
                if (!java.nio.file.Files.exists(socketPath)) {
                    log.error("Docker socket not found at /var/run/docker.sock");
                    log.error("Make sure Docker socket is mounted in docker-compose.yml");
                    throw new RuntimeException("Docker socket not accessible");
                }
                log.info("Docker socket found at /var/run/docker.sock");

                // Clear any environment variables that might override our config
                String dockerHostEnv = System.getenv("DOCKER_HOST");
                log.info("DOCKER_HOST environment variable: {}", dockerHostEnv);

                // Initialize Docker client for Linux/container environment
                DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost("unix:///var/run/docker.sock")
                    .withDockerTlsVerify(false)
                    .build();

                log.info("Docker host configured as: {}", config.getDockerHost());

                DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .maxConnections(100)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .responseTimeout(Duration.ofSeconds(45))
                    .build();

                dockerClient = DockerClientImpl.getInstance(config, httpClient);
                log.info("Docker client initialized for WireGuard container access");

                // Test the connection
                dockerClient.pingCmd().exec();
                log.info("Successfully connected to Docker daemon");
            } catch (Exception e) {
                log.error("Failed to initialize Docker client: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to connect to Docker daemon", e);
            }
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

    public static void main(String[] args) {
        WireGuardVpnAdapter adapter = new WireGuardVpnAdapter();
        // Manually call init since we're not in Spring context
        adapter.init();

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
            List<VpnClient> peers = adapter.getClients(interfaces.get(0));

            System.out.println("Found " + peers.size() + " peer(s):\n");

            for (int i = 0; i < peers.size(); i++) {
                VpnClient peer = peers.get(i);
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
