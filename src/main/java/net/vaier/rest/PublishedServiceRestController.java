package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase.PublishedServiceUco;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.IgnorePublishableServiceUseCase;
import net.vaier.application.PublishLanServiceUseCase;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.application.UnignorePublishableServiceUseCase;
import net.vaier.application.UpdatePublishedServiceUseCase;
import net.vaier.application.UpdatePublishedServiceUseCase.PublishedServicePatch;
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
    private final PublishLanServiceUseCase publishLanServiceUseCase;
    private final GetPublishableServicesUseCase getPublishableServicesUseCase;
    private final DeletePublishedServiceUseCase deletePublishedServiceUseCase;
    private final UpdatePublishedServiceUseCase updatePublishedServiceUseCase;
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
    public ResponseEntity<?> publishService(@RequestBody PublishRequest request) {
        log.info("Publishing service: {}:{} as {}.* (auth={}, directUrlDisabled={}, pathPrefix={})",
            request.address(), request.port(), request.subdomain(), request.requiresAuth(),
            request.directUrlDisabled(), request.pathPrefix());
        try {
            publishPeerServiceUseCase.publishService(
                request.address(), request.port(), request.subdomain(),
                request.requiresAuth(), request.rootRedirectPath(), request.directUrlDisabled(),
                request.pathPrefix());
        } catch (IllegalArgumentException e) {
            log.warn("Publish rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new PublishError(e.getMessage()));
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/lan")
    public ResponseEntity<?> publishLanService(@RequestBody PublishLanRequest request) {
        log.info("Publishing LAN service: {}://{}:{} as {}.* (auth={}, directUrlDisabled={}, redirect={}, pathPrefix={})",
            request.protocol(), request.machineName(), request.port(), request.subdomain(),
            request.requireAuth(), request.directUrlDisabled(), request.rootRedirectPath(),
            request.pathPrefix());
        try {
            publishLanServiceUseCase.publishLanService(
                request.subdomain(), request.machineName(), request.port(), request.protocol(),
                request.requireAuth(), request.directUrlDisabled(), request.rootRedirectPath(),
                request.pathPrefix());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            log.warn("LAN publish rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new PublishError(e.getMessage()));
        }
    }

    @PatchMapping("/{dnsName:.+}")
    public ResponseEntity<Void> updateService(@PathVariable String dnsName,
                                              @RequestParam(value = "pathPrefix", required = false) String pathPrefix,
                                              @RequestBody PublishedServicePatch patch) {
        log.info("Updating service {} (pathPrefix={}): {}", dnsName, pathPrefix, patch);
        updatePublishedServiceUseCase.updateService(dnsName, pathPrefix, patch);
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
    public ResponseEntity<Void> deleteService(@PathVariable String dnsName,
                                              @RequestParam(value = "pathPrefix", required = false) String pathPrefix) {
        log.info("Deleting published service: {} (pathPrefix: {})", dnsName, pathPrefix);
        try {
            deletePublishedServiceUseCase.deleteService(dnsName, pathPrefix);
        } catch (IllegalArgumentException e) {
            log.warn("Delete rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
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

    record PublishRequest(String address, int port, String subdomain, boolean requiresAuth, String rootRedirectPath,
                          boolean directUrlDisabled, String pathPrefix) {}
    record PublishLanRequest(String subdomain, String machineName, int port, String protocol, boolean requireAuth,
                             boolean directUrlDisabled, String rootRedirectPath, String pathPrefix) {}
    record PublishStatusResponse(boolean dnsPropagated, boolean traefikActive) {}
    record IgnoreRequest(String key) {}
    record PublishError(String message) {}
}
