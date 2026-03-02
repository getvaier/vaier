package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import net.vaier.domain.Server;
import net.vaier.domain.DockerService;
import net.vaier.domain.port.ForGettingServerInfo;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DockerServerAdapter implements ForGettingServerInfo {

    private final Map<String, DockerClient> dockerClientCache = new HashMap<>();

    @Override
    public List<DockerService> getServicesWithExposedPorts(Server server) {
        try {
            DockerClient dockerClient = getOrCreateDockerClient(server);

            // List all containers (both running and stopped)
            List<Container> containers = dockerClient.listContainersCmd()
                .withShowAll(true)
                .exec();

            List<DockerService> services = new ArrayList<>();

            for (Container container : containers) {
                List<DockerService.PortMapping> portMappings = extractPortMappings(container);

                // Only add services that have ports
                if (!portMappings.isEmpty()) {
                    String containerName = extractContainerName(container.getNames());

                    services.add(new DockerService(
                        container.getId(),
                        containerName,
                        container.getImage(),
                        portMappings
                    ));
                }
            }

            log.info("Found {} containers with exposed ports on {}", services.size(), server.getAddress());
            return services;

        } catch (Exception e) {
            log.error("Failed to get Docker services from {} - {}", server.getAddress(), e.getMessage());
            throw new RuntimeException("Failed to get Docker services from " + server.getAddress(), e);
        }
    }

    private DockerClient getOrCreateDockerClient(Server server) {
        String cacheKey = server.getAddress() + ":" + server.getPort();

        return dockerClientCache.computeIfAbsent(cacheKey, key -> {
            log.info("Creating Docker client connection to {}", server.dockerHostUrl());

            DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(server.dockerHostUrl())
                .withDockerTlsVerify(server.isTlsEnabled())
                .build();

            DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();

            DockerClient client = DockerClientImpl.getInstance(config, httpClient);

            // Test connection
            try {
                client.pingCmd().exec();
                log.info("Successfully connected to Docker daemon at {}", server.getAddress());
            } catch (Exception e) {
                log.error("Failed to connect to Docker daemon at {}: {}", server.getAddress(), e.getMessage());
                throw new RuntimeException("Failed to connect to Docker daemon at " + server.getAddress(), e);
            }

            return client;
        });
    }

    private List<DockerService.PortMapping> extractPortMappings(Container container) {
        List<DockerService.PortMapping> portMappings = new ArrayList<>();

        ContainerPort[] ports = container.getPorts();
        if (ports != null && ports.length > 0) {
            for (ContainerPort port : ports) {
                Integer publicPort = port.getPublicPort();
                Integer privatePort = port.getPrivatePort();
                String type = port.getType() != null ? port.getType() : "tcp";
                String ip = port.getIp() != null ? port.getIp() : "0.0.0.0";

                // Only add if we have a private port
                if (privatePort != null) {
                    portMappings.add(new DockerService.PortMapping(
                        privatePort,
                        publicPort != null ? publicPort : privatePort, // For host mode, use private port as public
                        type,
                        ip
                    ));
                }
            }
        }

        return portMappings;
    }

    private String extractContainerName(String[] names) {
        if (names == null || names.length == 0) {
            return "unknown";
        }
        // Docker names start with '/', so remove it
        String name = names[0];
        return name.startsWith("/") ? name.substring(1) : name;
    }

    @PreDestroy
    public void cleanup() {
        log.info("Closing {} Docker client connections", dockerClientCache.size());
        dockerClientCache.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                log.error("Error closing Docker client", e);
            }
        });
        dockerClientCache.clear();
    }

    public static void main(String[] args) {
        DockerServerAdapter adapter = new DockerServerAdapter();

        // Example: Connect to remote Docker host via TCP
        Server server = new Server(
            "localhost",  // hostname or IP
            2375,               // Docker daemon port (2375 for HTTP, 2376 for HTTPS)
            false               // TLS enabled?
        );

        System.out.println("Connecting to Docker at: " + server.dockerHostUrl());

        List<DockerService> services = adapter.getServicesWithExposedPorts(server);

        System.out.println("\nFound " + services.size() + " services with exposed ports:");
        services.forEach(service -> {
            System.out.println("\nContainer: " + service.containerName());
            System.out.println("  ID: " + service.containerId().substring(0, Math.min(12, service.containerId().length())));
            System.out.println("  Image: " + service.image());
            System.out.println("  Ports:");
            service.ports().forEach(port ->
                System.out.println("    " + port)
            );
        });

        adapter.cleanup();
    }
}
