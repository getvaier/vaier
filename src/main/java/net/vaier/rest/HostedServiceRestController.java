package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteHostedServiceUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.GetHostedServicesUseCase;
import net.vaier.application.GetLocalDockerServicesUseCase;
import net.vaier.application.ToggleServiceAuthUseCase;
import net.vaier.application.GetHostedServicesUseCase.HostedServiceUco;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.application.PublishPeerServiceUseCase.PublishableSource;
import net.vaier.application.service.PublishPeerServiceService;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/hosted-services")
@RequiredArgsConstructor
@Slf4j
public class HostedServiceRestController {

    private final GetHostedServicesUseCase getHostedServicesUseCase;
    private final PublishPeerServiceUseCase publishPeerServiceUseCase;
    private final PublishPeerServiceService publishPeerServiceService;
    private final DeleteHostedServiceUseCase deleteHostedServiceUseCase;
    private final ToggleServiceAuthUseCase toggleServiceAuthUseCase;
    private final DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    private final GetLocalDockerServicesUseCase getLocalDockerServicesUseCase;
    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @GetMapping("/discover")
    public List<HostedServiceUco> getHostedServices() {
        return getHostedServicesUseCase.getHostedServices();
    }

    @GetMapping("/publishable")
    public List<PublishableService> getPublishableServices() {
        var existingRoutes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        var publishable = new ArrayList<PublishableService>();

        discoverPeerContainersUseCase.discoverAll().stream()
            .filter(peer -> "OK".equals(peer.status()))
            .flatMap(peer -> peer.containers().stream()
                .flatMap(container -> container.ports().stream()
                    .filter(p -> "tcp".equals(p.type()))
                    .filter(p -> existingRoutes.stream()
                        .noneMatch(r -> r.getAddress().equals(peer.vpnIp()) && r.getPort() == p.publicPort()))
                    .map(p -> new PublishableService(PublishableSource.PEER, peer.peerName(), peer.vpnIp(), container.containerName(), p.publicPort(), null))
                )
            )
            .forEach(publishable::add);

        publishable.addAll(getLocalDockerServicesUseCase.getUnpublishedLocalServices(existingRoutes));

        return publishable.stream().distinct().toList();
    }

    @PostMapping("/publish")
    public ResponseEntity<Void> publishService(@RequestBody PublishRequest request) {
        log.info("Publishing service: {}:{} as {}.* (auth={})", request.address(), request.port(), request.subdomain(), request.requiresAuth());
        publishPeerServiceUseCase.publishService(request.address(), request.port(), request.subdomain(), request.requiresAuth(), request.rootRedirectPath());
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{dnsName:.+}/auth")
    public ResponseEntity<Void> setAuth(@PathVariable String dnsName, @RequestBody AuthRequest request) {
        log.info("Setting auth={} for {}", request.requiresAuth(), dnsName);
        toggleServiceAuthUseCase.setAuthentication(dnsName, request.requiresAuth());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{subdomain}/status")
    public PublishStatusResponse getPublishStatus(@PathVariable String subdomain) {
        var status = publishPeerServiceService.getPublishStatus(subdomain);
        return new PublishStatusResponse(status.dnsPropagated(), status.traefikActive());
    }

    @DeleteMapping("/{dnsName:.+}")
    public ResponseEntity<Void> deleteService(@PathVariable String dnsName) {
        log.info("Deleting hosted service: {}", dnsName);
        deleteHostedServiceUseCase.deleteService(dnsName);
        return ResponseEntity.ok().build();
    }

    record PublishRequest(String address, int port, String subdomain, boolean requiresAuth, String rootRedirectPath) {}
    record PublishStatusResponse(boolean dnsPropagated, boolean traefikActive) {}
    record AuthRequest(boolean requiresAuth) {}
}
