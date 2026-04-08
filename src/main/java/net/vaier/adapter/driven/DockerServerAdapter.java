package net.vaier.adapter.driven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetworkSettings;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import net.vaier.domain.Server;
import net.vaier.domain.DockerService;
import net.vaier.domain.port.ForGettingImageDigests;
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
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DockerServerAdapter implements ForGettingServerInfo, ForGettingImageDigests {

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
                    String image = container.getImage();
                    String version = resolveVersion(container.getImageId(), image, dockerClient);
                    List<String> networks = extractNetworks(container);

                    services.add(new DockerService(
                        container.getId(),
                        containerName,
                        image,
                        version,
                        portMappings,
                        networks
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
                    portMappings.add(new DockerService.PortMapping(privatePort, publicPort, type, ip));
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

    private List<String> extractNetworks(Container container) {
        ContainerNetworkSettings networkSettings = container.getNetworkSettings();
        if (networkSettings == null || networkSettings.getNetworks() == null) {
            return List.of();
        }
        return new ArrayList<>(networkSettings.getNetworks().keySet());
    }

    private String resolveVersion(String imageId, String image, DockerClient dockerClient) {
        try {
            InspectImageResponse info = dockerClient.inspectImageCmd(imageId).exec();
            java.util.Map<String, String> labels = info.getConfig() != null ? info.getConfig().getLabels() : null;
            return DockerService.versionFromLabels(labels, image);
        } catch (Exception e) {
            log.debug("Could not inspect image {} for version labels: {}", imageId, e.getMessage());
            return DockerService.versionFromLabels(null, image);
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

    @Override
    public Optional<String> getImageDigest(Server server, String imageId) {
        try {
            DockerClient dockerClient = getOrCreateDockerClient(server);
            InspectImageResponse info = dockerClient.inspectImageCmd(imageId).exec();
            List<String> repoDigests = info.getRepoDigests();
            if (repoDigests == null || repoDigests.isEmpty()) {
                return Optional.empty();
            }
            String repoDigest = repoDigests.get(0);
            int atIndex = repoDigest.indexOf('@');
            return atIndex >= 0
                    ? Optional.of(repoDigest.substring(atIndex + 1))
                    : Optional.of(repoDigest);
        } catch (Exception e) {
            log.debug("Could not get image digest for {} on {}: {}", imageId, server.getAddress(), e.getMessage());
            return Optional.empty();
        }
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

}
