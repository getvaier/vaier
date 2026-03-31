package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteHostedServiceUseCase;
import net.vaier.application.GetHostedServicesUseCase;
import net.vaier.application.GetHostedServicesUseCase.HostedServiceUco;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.application.ToggleServiceAuthUseCase;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/hosted-services")
@RequiredArgsConstructor
@Slf4j
public class HostedServiceRestController {

    private final GetHostedServicesUseCase getHostedServicesUseCase;
    private final PublishPeerServiceUseCase publishPeerServiceUseCase;
    private final GetPublishableServicesUseCase getPublishableServicesUseCase;
    private final DeleteHostedServiceUseCase deleteHostedServiceUseCase;
    private final ToggleServiceAuthUseCase toggleServiceAuthUseCase;
    private final SseEventPublisher sseEventPublisher;

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToEvents() {
        return sseEventPublisher.subscribe("hosted-services");
    }

    @GetMapping("/discover")
    public List<HostedServiceUco> getHostedServices() {
        return getHostedServicesUseCase.getHostedServices();
    }

    @GetMapping("/publishable")
    public List<PublishableService> getPublishableServices() {
        return getPublishableServicesUseCase.getPublishableServices();
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
        sseEventPublisher.publish("hosted-services", "service-updated", dnsName);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{subdomain}/status")
    public PublishStatusResponse getPublishStatus(@PathVariable String subdomain) {
        var status = publishPeerServiceUseCase.getPublishStatus(subdomain);
        return new PublishStatusResponse(status.dnsPropagated(), status.traefikActive());
    }

    @DeleteMapping("/{dnsName:.+}")
    public ResponseEntity<Void> deleteService(@PathVariable String dnsName) {
        log.info("Deleting hosted service: {}", dnsName);
        deleteHostedServiceUseCase.deleteService(dnsName);
        sseEventPublisher.publish("hosted-services", "service-updated", dnsName);
        return ResponseEntity.ok().build();
    }

    record PublishRequest(String address, int port, String subdomain, boolean requiresAuth, String rootRedirectPath) {}
    record PublishStatusResponse(boolean dnsPropagated, boolean traefikActive) {}
    record AuthRequest(boolean requiresAuth) {}
}
