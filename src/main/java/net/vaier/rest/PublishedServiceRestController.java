package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.EditServiceRedirectUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase.PublishedServiceUco;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.IgnorePublishableServiceUseCase;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.application.ToggleServiceAuthUseCase;
import net.vaier.application.UnignorePublishableServiceUseCase;
import net.vaier.adapter.driven.SseEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/published-services")
@RequiredArgsConstructor
@Slf4j
public class PublishedServiceRestController {

    private final GetPublishedServicesUseCase getPublishedServicesUseCase;
    private final PublishPeerServiceUseCase publishPeerServiceUseCase;
    private final GetPublishableServicesUseCase getPublishableServicesUseCase;
    private final DeletePublishedServiceUseCase deletePublishedServiceUseCase;
    private final ToggleServiceAuthUseCase toggleServiceAuthUseCase;
    private final EditServiceRedirectUseCase editServiceRedirectUseCase;
    private final IgnorePublishableServiceUseCase ignorePublishableServiceUseCase;
    private final UnignorePublishableServiceUseCase unignorePublishableServiceUseCase;
    private final SseEventPublisher sseEventPublisher;

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToEvents() {
        return sseEventPublisher.subscribe("published-services");
    }

    @GetMapping("/discover")
    public List<PublishedServiceUco> getPublishedServices() {
        return getPublishedServicesUseCase.getPublishedServices();
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
        sseEventPublisher.publish("published-services", "service-updated", dnsName);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{dnsName:.+}/redirect")
    public ResponseEntity<Void> setRedirect(@PathVariable String dnsName, @RequestBody RedirectRequest request) {
        log.info("Setting rootRedirectPath={} for {}", request.rootRedirectPath(), dnsName);
        editServiceRedirectUseCase.setRootRedirectPath(dnsName, request.rootRedirectPath());
        sseEventPublisher.publish("published-services", "service-updated", dnsName);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{subdomain}/status")
    public PublishStatusResponse getPublishStatus(@PathVariable String subdomain) {
        var status = publishPeerServiceUseCase.getPublishStatus(subdomain);
        return new PublishStatusResponse(status.dnsPropagated(), status.traefikActive());
    }

    @GetMapping("/pending")
    public List<PublishPeerServiceUseCase.PendingPublication> getPendingPublications() {
        return publishPeerServiceUseCase.getPendingPublications();
    }

    @DeleteMapping("/{dnsName:.+}")
    public ResponseEntity<Void> deleteService(@PathVariable String dnsName) {
        log.info("Deleting published service: {}", dnsName);
        deletePublishedServiceUseCase.deleteService(dnsName);
        sseEventPublisher.publish("published-services", "service-updated", dnsName);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/publishable/ignore")
    public ResponseEntity<Void> ignoreService(@RequestBody IgnoreRequest request) {
        ignorePublishableServiceUseCase.ignoreService(request.key());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/publishable/unignore")
    public ResponseEntity<Void> unignoreService(@RequestBody IgnoreRequest request) {
        unignorePublishableServiceUseCase.unignoreService(request.key());
        return ResponseEntity.ok().build();
    }

    record PublishRequest(String address, int port, String subdomain, boolean requiresAuth, String rootRedirectPath) {}
    record PublishStatusResponse(boolean dnsPropagated, boolean traefikActive) {}
    record AuthRequest(boolean requiresAuth) {}
    record RedirectRequest(String rootRedirectPath) {}
    record IgnoreRequest(String key) {}
}
