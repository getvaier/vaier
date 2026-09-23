package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.MachineId;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.GetOwnSignInsUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase.PublishedServiceUco;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.IgnorePublishableServiceUseCase;
import net.vaier.application.PublishLanServiceUseCase;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.domain.PublishableService;
import net.vaier.application.UnignorePublishableServiceUseCase;
import net.vaier.application.UpdatePublishedServiceUseCase;
import net.vaier.application.UpdatePublishedServiceUseCase.PublishedServicePatch;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSubscribingToEvents;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Locale;

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
    private final ForPublishingEvents forPublishingEvents;
    private final ForSubscribingToEvents forSubscribingToEvents;
    private final GetOwnSignInsUseCase getOwnSignInsUseCase;

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToEvents() {
        return forSubscribingToEvents.subscribe("published-services");
    }

    @GetMapping("/discover")
    public List<PublishedServiceUco> getPublishedServices() {
        return getPublishedServicesUseCase.getPublishedServices();
    }

    /** What each published service asks for by itself, as Vaier last saw its backend. */
    @GetMapping("/sign-ins")
    public List<OwnSignInResponse> signIns() {
        return getOwnSignInsUseCase.getOwnSignIns().stream()
            .map(found -> new OwnSignInResponse(found.dnsName(), found.pathPrefix(),
                found.ownSignIn().kind().name().toLowerCase(Locale.ROOT), found.ownSignIn().detail(),
                found.ownSignIn().app(), found.ownSignIn().observedAt().toString(),
                found.advice() == null ? null : found.advice().name().toLowerCase(Locale.ROOT)))
            .toList();
    }

    @GetMapping("/publishable")
    public List<PublishableService> getPublishableServices() {
        return getPublishableServicesUseCase.getPublishableServices();
    }

    @PostMapping("/publish")
    public ResponseEntity<Void> publishService(@RequestBody PublishRequest request) {
        log.info("Publishing service: {}:{} as {}.* (auth={}, directUrlDisabled={}, pathPrefix={}, stream={})",
            LogSafe.forLog(request.address()), request.port(), LogSafe.forLog(request.subdomain()),
            request.requiresAuth(), request.directUrlDisabled(), LogSafe.forLog(request.pathPrefix()),
            request.stream());
        // Validation failures surface as IllegalArgumentException and are rendered as a
        // uniform 400 ApiError by GlobalExceptionHandler — no hand-rolled error body here.
        publishPeerServiceUseCase.publishService(
            request.address(), request.port(), request.subdomain(),
            request.requiresAuth(), request.rootRedirectPath(), request.directUrlDisabled(),
            request.pathPrefix(), request.stream());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/lan")
    public ResponseEntity<Void> publishLanService(@RequestBody PublishLanRequest request) {
        log.info("Publishing LAN service: {}://{}:{} as {}.* (auth={}, directUrlDisabled={}, redirect={}, pathPrefix={})",
            LogSafe.forLog(request.protocol()), LogSafe.forLog(request.machineId()), request.port(),
            LogSafe.forLog(request.subdomain()), request.requireAuth(), request.directUrlDisabled(),
            LogSafe.forLog(request.rootRedirectPath()), LogSafe.forLog(request.pathPrefix()));
        publishLanServiceUseCase.publishLanService(
            request.subdomain(), MachineId.of(request.machineId()), request.port(), request.protocol(),
            request.requireAuth(), request.directUrlDisabled(), request.rootRedirectPath(),
            request.pathPrefix());
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{dnsName:.+}")
    public ResponseEntity<Void> updateService(@PathVariable String dnsName,
                                              @RequestParam(value = "pathPrefix", required = false) String pathPrefix,
                                              @RequestBody PublishedServicePatch patch) {
        String safePatch = LogSafe.forLog(String.valueOf(patch));
        log.info("Updating service {} (pathPrefix={}): {}",
            LogSafe.forLog(dnsName), LogSafe.forLog(pathPrefix), safePatch);
        updatePublishedServiceUseCase.updateService(dnsName, pathPrefix, patch);
        forPublishingEvents.publish("published-services", "service-updated", dnsName);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{subdomain}/status")
    public PublishStatusResponse getPublishStatus(@PathVariable String subdomain) {
        var status = publishPeerServiceUseCase.getPublishStatus(subdomain);
        return new PublishStatusResponse(status.traefikActive());
    }

    @GetMapping("/pending")
    public List<PublishPeerServiceUseCase.PendingPublication> getPendingPublications() {
        return publishPeerServiceUseCase.getPendingPublications();
    }

    @DeleteMapping("/{dnsName:.+}")
    public ResponseEntity<Void> deleteService(@PathVariable String dnsName,
                                              @RequestParam(value = "pathPrefix", required = false) String pathPrefix) {
        log.info("Deleting published service: {} (pathPrefix: {})",
            LogSafe.forLog(dnsName), LogSafe.forLog(pathPrefix));
        // A rejected delete (IllegalArgumentException) propagates to GlobalExceptionHandler
        // as a uniform 400 ApiError, like every other validation failure.
        deletePublishedServiceUseCase.deleteService(dnsName, pathPrefix);
        forPublishingEvents.publish("published-services", "service-updated", dnsName);
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

    /** {@code stream} says the port serves raw TCP rather than a website — published by SNI on 443. */
    record PublishRequest(String address, int port, String subdomain, boolean requiresAuth, String rootRedirectPath,
                          boolean directUrlDisabled, String pathPrefix, boolean stream) {}
    record PublishLanRequest(String subdomain, String machineId, int port, String protocol, boolean requireAuth,
                             boolean directUrlDisabled, String rootRedirectPath, String pathPrefix) {}
    record PublishStatusResponse(boolean traefikActive) {}
    record IgnoreRequest(String key) {}
    record OwnSignInResponse(String dnsName, String pathPrefix, String kind, String detail, String app,
                             String observedAt, String advice) {}
}
