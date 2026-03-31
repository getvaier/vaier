package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetLocalDockerServicesUseCase;
import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.application.PublishPeerServiceUseCase.PublishableSource;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForGettingServerInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class GetLocalDockerServicesService implements GetLocalDockerServicesUseCase {

    private static final Set<String> EXCLUDED_NAMES = Set.of(
        "wireguard", "wireguard-masquerade", "authelia", "redis", "vaier"
    );

    // Known services with constrained ports and a root redirect path when applicable
    private record KnownService(Set<Integer> allowedPorts, String rootRedirectPath) {}

    private static final Map<String, KnownService> KNOWN_SERVICES = Map.of(
        "traefik", new KnownService(Set.of(8080), "/dashboard/")
    );

    private final ForGettingServerInfo forGettingServerInfo;

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
                    .filter(p -> existingRoutes.stream()
                        .noneMatch(r -> r.getAddress().equals(container.containerName()) && r.getPort() == p.privatePort()))
                    .forEach(p -> result.add(new PublishableService(
                        PublishableSource.LOCAL,
                        null,
                        container.containerName(),
                        container.containerName(),
                        p.privatePort(),
                        known != null ? known.rootRedirectPath() : null
                    )));
            });
        } catch (Exception e) {
            log.warn("Failed to query local Docker socket: {}", e.getMessage());
        }
        return result;
    }
}
