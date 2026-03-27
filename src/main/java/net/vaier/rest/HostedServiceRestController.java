package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.GetHostedServicesUseCase;
import net.vaier.application.GetHostedServicesUseCase.HostedServiceUco;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/hosted-services")
@RequiredArgsConstructor
@Slf4j
public class HostedServiceRestController {

    private final GetHostedServicesUseCase getHostedServicesUseCase;
    private final PublishPeerServiceUseCase publishPeerServiceUseCase;
    private final DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @GetMapping("/discover")
    public List<HostedServiceUco> getHostedServices() {
        return getHostedServicesUseCase.getHostedServices();
    }

    @GetMapping("/publishable")
    public List<PublishableService> getPublishableServices() {
        var existingRoutes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();

        return discoverPeerContainersUseCase.discoverAll().stream()
            .filter(peer -> "OK".equals(peer.status()))
            .flatMap(peer -> peer.containers().stream()
                .flatMap(container -> container.ports().stream()
                    .filter(p -> "tcp".equals(p.type()))
                    .filter(p -> existingRoutes.stream()
                        .noneMatch(r -> r.getAddress().equals(peer.vpnIp()) && r.getPort() == p.publicPort()))
                    .map(p -> new PublishableService(peer.peerName(), peer.vpnIp(), container.containerName(), p.publicPort()))
                )
            )
            .distinct()
            .toList();
    }

    @PostMapping("/publish")
    public ResponseEntity<Void> publishService(@RequestBody PublishRequest request) {
        log.info("Publishing service: {}:{} as {}.* (auth={})", request.peerIp(), request.port(), request.subdomain(), request.requiresAuth());
        publishPeerServiceUseCase.publishService(request.peerIp(), request.port(), request.subdomain(), request.requiresAuth());
        return ResponseEntity.ok().build();
    }

    record PublishRequest(String peerIp, int port, String subdomain, boolean requiresAuth) {}
}
