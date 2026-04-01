package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetLocalDockerServicesUseCase;
import net.vaier.config.ServiceNames;
import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.application.PublishPeerServiceUseCase.PublishableSource;
import net.vaier.domain.DockerService;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForGettingServerInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class GetLocalDockerServicesService implements GetLocalDockerServicesUseCase {

    private static final Set<String> EXCLUDED_NAMES = Set.of(
        ServiceNames.WIREGUARD, ServiceNames.WIREGUARD_MASQUERADE,
        ServiceNames.AUTHELIA, ServiceNames.REDIS, ServiceNames.VAIER
    );

    // Known services with constrained ports and a root redirect path when applicable
    private record KnownService(Set<Integer> allowedPorts, String rootRedirectPath) {}

    private static final Map<String, KnownService> KNOWN_SERVICES = Map.of(
        ServiceNames.TRAEFIK, new KnownService(Set.of(8080), "/dashboard/")
    );

    private final ForGettingServerInfo forGettingServerInfo;
    private final String vaierNetworkName;
    private final String dockerGatewayIp;

    @Autowired
    public GetLocalDockerServicesService(ForGettingServerInfo forGettingServerInfo) {
        this(forGettingServerInfo,
            System.getenv().getOrDefault("VAIER_NETWORK_NAME", "vaier-network"),
            System.getenv().getOrDefault("VAIER_DOCKER_GATEWAY", "172.20.0.1"));
    }

    GetLocalDockerServicesService(ForGettingServerInfo forGettingServerInfo, String vaierNetworkName, String dockerGatewayIp) {
        this.forGettingServerInfo = forGettingServerInfo;
        this.vaierNetworkName = vaierNetworkName;
        this.dockerGatewayIp = dockerGatewayIp;
    }

    @Override
    public List<PublishableService> getUnpublishedLocalServices(List<ReverseProxyRoute> existingRoutes) {
        List<PublishableService> result = new ArrayList<>();
        try {
            forGettingServerInfo.getServicesWithExposedPorts(Server.local()).forEach(container -> {
                String name = container.containerName().toLowerCase();
                if (EXCLUDED_NAMES.contains(name)) return;

                KnownService known = KNOWN_SERVICES.get(name);

                container.ports().stream()
                    .filter(p -> "tcp".equals(p.type()))
                    .filter(p -> known == null || known.allowedPorts().contains(p.privatePort()))
                    .forEach(p -> {
                        ServiceEndpoint ep = resolveEndpoint(container, p);
                        if (ep == null) return;
                        if (existingRoutes.stream().anyMatch(r -> r.getAddress().equals(ep.address()) && r.getPort() == ep.port())) return;
                        result.add(new PublishableService(
                            PublishableSource.LOCAL,
                            null,
                            ep.address(),
                            container.containerName(),
                            ep.port(),
                            known != null ? known.rootRedirectPath() : null
                        ));
                    });
            });
        } catch (Exception e) {
            log.warn("Failed to query local Docker socket: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Determines the address and port Traefik should use to reach this container.
     * Containers on the vaier network (or with unknown networks) are reachable by container name + private port.
     * Containers on other networks must have a host-mapped public port and are reached via the Docker gateway IP.
     * Returns null if the container is unreachable from Traefik.
     */
    private ServiceEndpoint resolveEndpoint(DockerService container, PortMapping p) {
        boolean onVaierNetwork = container.networks().isEmpty() || container.networks().contains(vaierNetworkName);
        if (onVaierNetwork) {
            return new ServiceEndpoint(container.containerName(), p.privatePort());
        }
        if (p.publicPort() != null) {
            return new ServiceEndpoint(dockerGatewayIp, p.publicPort());
        }
        return null;
    }

    private record ServiceEndpoint(String address, int port) {}
}
