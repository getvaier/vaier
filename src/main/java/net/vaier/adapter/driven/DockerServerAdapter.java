package net.vaier.adapter.driven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private final Map<String, Server> serverCache = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                List<DockerService.PortMapping> portMappings = extractPortMappings(container, dockerClient);

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

        serverCache.put(cacheKey, server);
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

    private List<DockerService.PortMapping> extractPortMappings(Container container, DockerClient dockerClient) {
        boolean isHostNetwork = container.getHostConfig() != null
            && "host".equals(container.getHostConfig().getNetworkMode());

        if (isHostNetwork) {
            return extractHostNetworkPortMappings(container, dockerClient);
        }

        List<DockerService.PortMapping> portMappings = new ArrayList<>();
        ContainerPort[] ports = container.getPorts();
        if (ports != null) {
            for (ContainerPort port : ports) {
                Integer privatePort = port.getPrivatePort();
                if (privatePort != null) {
                    Integer publicPort = port.getPublicPort();
                    String type = port.getType() != null ? port.getType() : "tcp";
                    String ip = port.getIp() != null ? port.getIp() : "0.0.0.0";
                    portMappings.add(new DockerService.PortMapping(privatePort, publicPort != null ? publicPort : privatePort, type, ip));
                }
            }
        }
        return portMappings;
    }

    private List<DockerService.PortMapping> extractHostNetworkPortMappings(Container container, DockerClient dockerClient) {
        String cacheKey = dockerClientCache.entrySet().stream()
            .filter(e -> e.getValue() == dockerClient)
            .map(Map.Entry::getKey)
            .findFirst().orElse(null);
        Server server = cacheKey != null ? serverCache.get(cacheKey) : null;

        // Only direct HTTP works reliably for remote TCP servers due to docker-java Capability enum deserialization bug
        if (server == null || server.getAddress().startsWith("/") || server.getAddress().startsWith("unix://")) {
            return List.of();
        }

        try {
            int dockerPort = server.getPort() != null ? server.getPort() : 2375;
            String url = "http://" + server.getAddress() + ":" + dockerPort + "/containers/" + container.getId() + "/json";

            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode exposedPorts = objectMapper.readTree(response.body()).path("Config").path("ExposedPorts");
            List<DockerService.PortMapping> portMappings = new ArrayList<>();
            exposedPorts.fieldNames().forEachRemaining(portSpec -> {
                String[] parts = portSpec.split("/");
                if (parts.length == 2) {
                    int port = Integer.parseInt(parts[0]);
                    portMappings.add(new DockerService.PortMapping(port, port, parts[1], "0.0.0.0"));
                }
            });
            return portMappings;
        } catch (Exception e) {
            log.warn("Failed to get exposed ports for host-network container {}: {}", container.getNames()[0], e.getMessage());
            return List.of();
        }
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
