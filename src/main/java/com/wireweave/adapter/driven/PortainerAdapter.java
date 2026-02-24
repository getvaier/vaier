package com.wireweave.adapter.driven;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wireweave.domain.DockerHost;
import com.wireweave.domain.DockerService;
import com.wireweave.domain.port.ForGettingDockerInfo;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PortainerAdapter implements ForGettingDockerInfo {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PortainerAdapter() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<DockerService> getServicesWithExposedPorts(DockerHost dockerHost) {
        try {
            // First, get the endpoint ID (usually 1 or 2 for local Docker)
            int endpointId = getEndpointId(dockerHost);

            // Get list of containers from Portainer API
            String containersUrl = dockerHost.endpointsUrl() + "/" + endpointId + "/docker/containers/json";

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(containersUrl))
                    .GET();

            requestBuilder.header("X-API-Key", dockerHost.getApiToken());

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to get containers from Portainer. Status: " +
                        response.statusCode() + ", Body: " + response.body());
            }

            // Parse response
            PortainerContainer[] containers = objectMapper.readValue(response.body(), PortainerContainer[].class);

            List<DockerService> services = new ArrayList<>();
            for (PortainerContainer container : containers) {
                List<DockerService.PortMapping> portMappings = new ArrayList<>();

                // Handle regular port mappings
                if (container.ports != null && container.ports.length > 0) {
                    for (PortainerPort port : container.ports) {
                        portMappings.add(new DockerService.PortMapping(
                                port.privatePort,
                                port.publicPort,
                                port.type != null ? port.type : "tcp",
                                port.ip
                        ));
                    }
                }
                // Handle host network mode - need to inspect container for exposed ports
                else if (container.hostConfig != null && "host".equals(container.hostConfig.networkMode)) {
                    portMappings = getExposedPortsForHostMode(dockerHost, endpointId, container.id);
                }

                // Only add services that have ports
                if (!portMappings.isEmpty()) {
                    String containerName = extractContainerName(container.names);

                    services.add(new DockerService(
                            container.id,
                            containerName,
                            container.image,
                            portMappings
                    ));
                }
            }

            return services;

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to get Docker services from Portainer at " + dockerHost, e);
        }
    }

    private List<DockerService.PortMapping> getExposedPortsForHostMode(DockerHost dockerHost, int endpointId, String containerId) {
        try {
            String inspectUrl = dockerHost.endpointsUrl() + "/" + endpointId + "/docker/containers/" + containerId + "/json";

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(inspectUrl))
                    .GET();

            requestBuilder.header("X-API-Key", dockerHost.getApiToken());

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return List.of(); // Return empty list if inspection fails
            }

            ContainerInspect containerDetails = objectMapper.readValue(response.body(), ContainerInspect.class);

            List<DockerService.PortMapping> portMappings = new ArrayList<>();

            // Get exposed ports from container config
            if (containerDetails.config != null && containerDetails.config.exposedPorts != null) {
                for (String portSpec : containerDetails.config.exposedPorts.keySet()) {
                    // Port spec format is like "51820/udp" or "80/tcp"
                    String[] parts = portSpec.split("/");
                    if (parts.length >= 1) {
                        try {
                            int port = Integer.parseInt(parts[0]);
                            String protocol = parts.length > 1 ? parts[1] : "tcp";

                            // For host mode, publicPort and privatePort are the same
                            // and they're accessible on all host interfaces (0.0.0.0)
                            portMappings.add(new DockerService.PortMapping(
                                    port,
                                    port,
                                    protocol,
                                    "0.0.0.0"
                            ));
                        } catch (NumberFormatException e) {
                            // Skip invalid port numbers
                        }
                    }
                }
            }

            return portMappings;

        } catch (IOException | InterruptedException e) {
            return List.of(); // Return empty list on error
        }
    }

    private int getEndpointId(DockerHost dockerHost) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(dockerHost.endpointsUrl()))
                .GET();

        requestBuilder.header("X-API-Key", dockerHost.getApiToken());

        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to get endpoints from Portainer. Status: " +
                    response.statusCode() + ". Body: " + response.body());
        }

        PortainerEndpoint[] endpoints = objectMapper.readValue(response.body(), PortainerEndpoint[].class);

        if (endpoints.length == 0) {
            throw new RuntimeException("No endpoints found in Portainer");
        }

        // Return the first endpoint (usually the local Docker environment)
        return endpoints[0].id;
    }

    private String extractContainerName(String[] names) {
        if (names == null || names.length == 0) {
            return "unknown";
        }
        // Docker names start with '/', so remove it
        String name = names[0];
        return name.startsWith("/") ? name.substring(1) : name;
    }

    // JSON mapping classes
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PortainerContainer {
        @JsonProperty("Id")
        public String id;
        @JsonProperty("Names")
        public String[] names;
        @JsonProperty("Image")
        public String image;
        @JsonProperty("Ports")
        public PortainerPort[] ports;
        @JsonProperty("HostConfig")
        public HostConfig hostConfig;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class HostConfig {
        @JsonProperty("NetworkMode")
        public String networkMode;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ContainerInspect {
        @JsonProperty("Config")
        public ContainerConfig config;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ContainerConfig {
        @JsonProperty("ExposedPorts")
        public java.util.Map<String, Object> exposedPorts;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PortainerPort {
        @JsonProperty("PrivatePort")
        public int privatePort;
        @JsonProperty("PublicPort")
        public Integer publicPort;
        @JsonProperty("Type")
        public String type;
        @JsonProperty("IP")
        public String ip;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PortainerEndpoint {
        @JsonProperty("Id")
        public int id;
        @JsonProperty("Name")
        public String name;
    }

    public static void main(String[] args) {
        PortainerAdapter adapter = new PortainerAdapter();

        DockerHost dockerHost = new DockerHost(
            "wireguard.home",
            System.getenv("WIREWEAVE_PORTAINER_TOKEN")
        );

        System.out.println("Connecting to Portainer at: " + dockerHost);

        List<DockerService> services = adapter.getServicesWithExposedPorts(dockerHost);

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
    }
}
