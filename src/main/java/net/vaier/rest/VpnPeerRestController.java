package net.vaier.rest;

import net.vaier.application.ClaimDeviceUseCase;
import net.vaier.application.CreatePeerUseCase;
import net.vaier.application.DeletePeerUseCase;
import net.vaier.application.EnrolDeviceUseCase;
import net.vaier.application.ForgetMyPositionUseCase;
import net.vaier.application.GetMyDeviceUseCase;
import net.vaier.application.GenerateDockerComposeUseCase;
import net.vaier.application.GeneratePeerSetupScriptUseCase;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetServerLocationUseCase;
import net.vaier.application.GetVpnPeersUseCase;
import net.vaier.application.GetVpnPeersUseCase.VpnPeerView;
import net.vaier.application.CheckStandingUseCase;
import net.vaier.application.LeaveFleetUseCase;
import net.vaier.application.ReissuePeerConfigUseCase;
import net.vaier.application.RenamePeerUseCase;
import net.vaier.application.ReportMyPositionUseCase;
import net.vaier.application.UpdateLanCidrUseCase;
import net.vaier.application.UpdatePeerDeviceCategoryUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.CallerIp;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.LanAddress;
import net.vaier.domain.MachineId;
import net.vaier.domain.PeerArtifact;
import net.vaier.domain.Placement;
import net.vaier.domain.ReportedPosition;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSubscribingToEvents;
import net.vaier.domain.port.ForTrackingPeerConfigRetrieval;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import net.vaier.domain.port.ForVendingSetupTokens;
import net.vaier.config.ServiceNames;
import net.vaier.domain.MachineIntent;
import net.vaier.domain.MachineType;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/vpn/peers")
@RequiredArgsConstructor
@Slf4j
public class VpnPeerRestController {

    private final GetVpnPeersUseCase getVpnPeersUseCase;
    private final GetPeerConfigUseCase getPeerConfigUseCase;
    private final CreatePeerUseCase createPeerUseCase;
    private final DeletePeerUseCase deletePeerUseCase;
    private final EnrolDeviceUseCase enrolDeviceUseCase;
    private final LeaveFleetUseCase leaveFleetUseCase;
    private final CheckStandingUseCase checkStandingUseCase;
    private final GenerateDockerComposeUseCase generateDockerComposeUseCase;
    private final GeneratePeerSetupScriptUseCase generatePeerSetupScriptUseCase;
    private final UpdateLanCidrUseCase updateLanCidrUseCase;
    private final RenamePeerUseCase renamePeerUseCase;
    private final ReissuePeerConfigUseCase reissuePeerConfigUseCase;
    private final UpdatePeerDeviceCategoryUseCase updatePeerDeviceCategoryUseCase;
    private final ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    private final ForTrackingPeerConfigRetrieval forTrackingPeerConfigRetrieval;
    private final ForVendingSetupTokens forVendingSetupTokens;
    private final ForPublishingEvents forPublishingEvents;
    private final ForSubscribingToEvents forSubscribingToEvents;
    private final GetServerLocationUseCase getServerLocationUseCase;
    private final ReportMyPositionUseCase reportMyPositionUseCase;
    private final ForgetMyPositionUseCase forgetMyPositionUseCase;
    private final ClaimDeviceUseCase claimDeviceUseCase;
    private final GetMyDeviceUseCase getMyDeviceUseCase;
    private final ConfigResolver configResolver;

    @Value("${vaier.trusted-proxy-cidr:${launchpad.trusted-proxy-cidr:172.20.0.0/16}}")
    private String trustedProxyCidr;

    /**
     * The device claim cookie. Not an auth credential — it authorises exactly one thing, "record this
     * machine's position" — but still a bearer token, so it is HttpOnly, Secure and SameSite=Lax, and
     * scoped to the one path family that reads it.
     */
    static final String CLAIM_COOKIE = "vaier_device_claim";
    private static final String CLAIM_COOKIE_PATH = "/vpn/peers";
    private static final Duration CLAIM_COOKIE_LIFE = Duration.ofDays(1825);

    /**
     * One-shot 410 response body (#202). Returned from any of the five secret-bearing endpoints
     * after the peer's config has already been retrieved once. The fields are machine-readable so
     * the UI can render a useful error: the only way to recover a fresh config is delete +
     * recreate of the peer (which rotates the WireGuard keypair as a side effect).
     */
    private static final java.util.Map<String, String> ALREADY_VIEWED_BODY =
        java.util.Map.of("reason", "already-viewed", "action", "delete-and-recreate");

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToEvents() {
        return forSubscribingToEvents.subscribe("vpn-peers");
    }

    @GetMapping
    public ResponseEntity<List<VpnPeerResponse>> listPeers() {
        log.info("Fetching all VPN peers");
        try {
            List<VpnPeerResponse> response = getVpnPeersUseCase.getVpnPeers().stream()
                    .map(VpnPeerRestController::toResponse)
                    .toList();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to fetch VPN peers: {}", e.getMessage(), e);
            // Return empty list instead of error to prevent constant error messages
            return ResponseEntity.ok(List.of());
        }
    }

    private static VpnPeerResponse toResponse(VpnPeerView v) {
        return new VpnPeerResponse(
            v.id(), v.machineId(), v.name(), v.publicKey(), v.allowedIps(), v.tunnelIp(),
            v.endpointIp(), v.endpointPort(), v.latestHandshake(),
            v.connected(), v.transferRx(), v.transferTx(),
            v.peerType().name(), v.isServer(), v.isClient(), v.isRelay(),
            v.availableArtifacts().stream().map(Enum::name).sorted().toList(),
            v.deviceHeldKey(),
            v.lanCidr(), v.lanAddress(), v.description(),
            v.geoLocation().map(GeoLocation::latitude).orElse(null),
            v.geoLocation().map(GeoLocation::longitude).orElse(null),
            v.geoLocation().map(GeoLocation::city).orElse(null),
            v.geoLocation().map(GeoLocation::country).orElse(null),
            v.configOutOfDate(),
            v.deviceCategory().name(), v.deviceCategoryOverridden(), v.sshAccess(),
            v.placement().map(VpnPeerRestController::toPlacementResponse).orElse(null),
            v.positionTrail().points().stream().map(VpnPeerRestController::toTrailPointResponse).toList(),
            v.lastServiceReached().map(VpnPeerRestController::toLastServiceReachedResponse).orElse(null));
    }

    private static TrailPointResponse toTrailPointResponse(ReportedPosition point) {
        return new TrailPointResponse(point.latitude(), point.longitude(), point.accuracyMetres(),
            point.reportedAt());
    }

    private static LastServiceReachedResponse toLastServiceReachedResponse(
            GetVpnPeersUseCase.LastServiceReachedView reached) {
        return new LastServiceReachedResponse(reached.host(), reached.displayName(), reached.at());
    }

    private static PlacementResponse toPlacementResponse(Placement p) {
        return new PlacementResponse(p.latitude(), p.longitude(), p.source().name(), p.asOf(),
            p.accuracyMetres(), p.stale(), p.place());
    }

    @GetMapping("/server-location")
    public ResponseEntity<ServerLocationResponse> getServerLocation() {
        try {
            return getServerLocationUseCase.getServerLocation()
                .map(loc -> ResponseEntity.ok(new ServerLocationResponse(
                    loc.publicHost(),
                    loc.latitude(),
                    loc.longitude(),
                    loc.city(),
                    loc.country(),
                    loc.lanCidr()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Failed to fetch server location: {}", e.getMessage(), e);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Records where the calling device says it is. The device is identified from its tunnel IP or its
     * device claim, in the domain — deliberately never from the body, which carries coordinates and
     * nothing that could name a machine, so a device can only ever report its own position.
     */
    @PostMapping("/my-position")
    public ResponseEntity<Void> reportMyPosition(
            @RequestBody(required = false) ReportMyPositionRequest request,
            @CookieValue(name = CLAIM_COOKIE, required = false) String claimToken,
            HttpServletRequest httpRequest) {
        // No body at all is the domain's own refusal, borrowed — it cannot be reached with nothing to judge.
        if (request == null) {
            throw ReportedPosition.withoutCoordinates();
        }
        reportMyPositionUseCase.reportMyPosition(resolveCallerIp(httpRequest), claimToken,
            request.latitude(), request.longitude(), request.accuracyMetres());
        forPublishingEvents.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.noContent().build();
    }

    /**
     * The privacy escape hatch: forgets the position AND revokes the claim, then clears the cookie.
     *
     * <p>Mapped on the literal {@code /my-position} rather than the {@code /{peerIdentifier}} delete
     * below — Spring prefers the literal segment, so a peer delete is unaffected.
     */
    @DeleteMapping("/my-position")
    public ResponseEntity<Void> forgetMyPosition(
            @CookieValue(name = CLAIM_COOKIE, required = false) String claimToken,
            HttpServletRequest httpRequest) {
        forgetMyPositionUseCase.forgetMyPosition(resolveCallerIp(httpRequest), claimToken);
        forPublishingEvents.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, claimCookie("", Duration.ZERO).toString())
            .build();
    }

    /**
     * Which machine THIS browser has claimed, or null. The cookie is HttpOnly, so the browser cannot work
     * this out for itself — and the answer belongs here rather than on the peer records, because a claim
     * is a property of the browser asking, not of the machine.
     */
    @GetMapping("/my-device")
    public ResponseEntity<MyDeviceResponse> getMyDevice(
            @CookieValue(name = CLAIM_COOKIE, required = false) String claimToken) {
        return ResponseEntity.ok(new MyDeviceResponse(
            getMyDeviceUseCase.myDevice(claimToken).map(MachineId::value).orElse(null)));
    }

    /**
     * Claims this browser as {@code machineId}'s device. The machine id is in the path, and that is
     * correct here: this is the operator asserting from an authorised console session which device they
     * are on, not a device asserting its own identity. Gated like every other peer-mutating endpoint —
     * by the admin forward-auth chain in front of Vaier, with no exemption of its own.
     *
     * <p><b>Note the path variable.</b> Every sibling under {@code /vpn/peers/{...}} takes a <em>peer
     * identifier</em> — the config directory name, as {@code /{peerId}/reissue} and
     * {@code /{peerIdentifier}/config} do. This one takes the <em>machine id</em>, the identity a claim
     * hangs off, because a peer id is a storage key and a claim must survive one changing. A caller that
     * reuses a peer id here gets a {@code 404}; take the {@code machineId} field from {@code GET
     * /vpn/peers} instead.
     */
    @PostMapping("/{machineId}/device-claim")
    public ResponseEntity<Void> claimDevice(@PathVariable String machineId) {
        log.info("Claiming this browser as the device for machine {}", LogSafe.forLog(machineId));
        String token = claimDeviceUseCase.claimDevice(machineId);
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, claimCookie(token, CLAIM_COOKIE_LIFE).toString())
            .build();
    }

    private static ResponseCookie claimCookie(String value, Duration maxAge) {
        return ResponseCookie.from(CLAIM_COOKIE, value)
            .httpOnly(true).secure(true).sameSite("Lax").path(CLAIM_COOKIE_PATH).maxAge(maxAge).build();
    }

    /**
     * Which hop to believe is {@link CallerIp}'s decision, not this controller's — the launchpad and the
     * forward-auth check ask the same question, and a second copy of the rule here is the copy that drifts.
     */
    private String resolveCallerIp(HttpServletRequest request) {
        return CallerIp.of(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), trustedProxyCidr)
            .value();
    }

    @PostMapping
    public ResponseEntity<CreatePeerResponse> createPeer(@RequestBody CreatePeerRequest request) {
        log.info("Creating new VPN peer: {}", LogSafe.forLog(request.name()));

        // The intent -> MachineType mapping is a domain decision; when the intent-first flow supplies
        // it, delegate to MachineIntent and hand the resolved type to the unchanged use case. Absent an
        // intent, the legacy explicit peerType path is preserved unchanged.
        MachineType peerType = request.intent() != null
                ? request.intent().toMachineType(Boolean.TRUE.equals(request.windows()))
                : request.peerType();

        CreatePeerUseCase.CreatedPeerUco createdPeer = createPeerUseCase.createPeer(
                request.name(),
                peerType,
                request.lanCidr(),
                request.lanAddress(),
                request.description()
        );

        // Inline every artefact so the create-success modal renders config + QR + download buttons
        // in one response, without follow-up GETs. The five GET endpoints are gated by a one-shot
        // marker (#202); the marker is set on first GET, NOT on create. The UI uses only the
        // inline payload so it never burns the budget; a raw curl GET can still recover any one
        // artefact once (then 410 forever).
        CreatePeerResponse response = buildConfigDeliveryResponse(
                createdPeer.id(), createdPeer.machineId(), createdPeer.name(), createdPeer.ipAddress(),
                createdPeer.publicKey(), createdPeer.clientConfigFile(), createdPeer.peerType());

        forPublishingEvents.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.ok(response);
    }

    /**
     * {@code Enrol} a device that minted its own WireGuard keypair (#359 slice 1).
     *
     * <p>Admin-tier like every other {@code /vpn/peers} route — the operator reaches it from the Explorer
     * having signed in, and the device's public key travelled in on that same signed-in browser.
     *
     * <p>The response deliberately carries no key: the config it returns has no {@code PrivateKey} line,
     * because the private half was minted on the device and has never existed here.
     */
    @PostMapping("/enrol")
    public ResponseEntity<EnrolDeviceResponse> enrolDevice(@RequestBody EnrolDeviceRequest request) {
        log.info("Enrolling device: {}", LogSafe.forLog(request.name()));
        // The key is judged in the domain (IllegalArgumentException -> 400); no second copy of the rule here.
        EnrolDeviceUseCase.EnrolledDeviceUco enrolled =
            enrolDeviceUseCase.enrol(request.name(), request.publicKey());

        forPublishingEvents.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.ok(new EnrolDeviceResponse(
            enrolled.id(), enrolled.machineId() == null ? null : enrolled.machineId().value(),
            enrolled.name(), enrolled.ipAddress(), enrolled.configFile()));
    }

    /**
     * ANONYMOUS. A phone removes itself from the fleet (#359 slice 1b) — forgetting the tunnel on the
     * handset alone would leave the peer standing here forever, which is how a fleet fills up with
     * devices nobody has any more.
     *
     * <p>The phone has no session and never had one, so what it presents instead is the preshared key
     * it was handed at approval: a secret only that phone and Vaier hold, and one Vaier already has in
     * the peer's config on disk. The judgement is {@code PeerProof}'s, and it is offered only to a
     * peer with a {@code Device-held key}.
     *
     * <p>{@code 404} covers an unknown key and a wrong preshared key alike — trying keys here must
     * teach a caller nothing about which peers exist. Neither key is ever logged.
     */
    @PostMapping("/leave")
    public ResponseEntity<Void> leaveFleet(@RequestBody LeaveFleetRequest request) {
        if (!leaveFleetUseCase.leave(request.publicKey(), request.presharedKey())) {
            return ResponseEntity.notFound().build();
        }
        log.info("A device removed itself from the fleet");
        forPublishingEvents.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.noContent().build();
    }

    /**
     * ANONYMOUS, on the same rate-limited router as {@code /leave}. A phone whose handshakes have
     * stopped asks whether it was removed. Same proof, same {@code 404} for both failures, and it
     * changes nothing.
     */
    @PostMapping("/standing")
    public ResponseEntity<Void> standing(@RequestBody StandingRequest request) {
        return checkStandingUseCase.isMember(request.publicKey(), request.presharedKey())
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    /**
     * Reissues a peer's config (#247): re-renders it from current generation logic with the
     * keypair preserved, persists it, re-opens the one-shot retrieval budget, and returns the
     * fresh config + artefacts inline — the same shape as create, so the UI reuses the
     * create-success modal. 404 when the peer is unknown, 409 when the peer holds its own key.
     */
    @PostMapping("/{peerId}/reissue")
    public ResponseEntity<?> reissuePeer(@PathVariable String peerId) {
        log.info("Reissuing config for peer: {}", LogSafe.forLog(peerId));
        // PeerNotFoundException -> 404 and anything else -> 500 are rendered as ApiError
        // by GlobalExceptionHandler; no per-endpoint catch ladder needed.
        ReissuePeerConfigUseCase.ReissuedPeerUco reissued =
            reissuePeerConfigUseCase.reissuePeerConfig(peerId);

        CreatePeerResponse response = buildConfigDeliveryResponse(
                reissued.id(), reissued.machineId(), reissued.name(), reissued.ipAddress(),
                reissued.publicKey(), reissued.clientConfigFile(), reissued.peerType());

        forPublishingEvents.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.ok(response);
    }

    /**
     * Builds the inline config-delivery payload (config text + QR + docker-compose + setup script)
     * shared by create and reissue. The five GET endpoints stay gated by the one-shot marker (#202);
     * the UI consumes only this inline payload so it never burns the budget.
     */
    private CreatePeerResponse buildConfigDeliveryResponse(String id, MachineId machineId, String name,
            String ipAddress, String publicKey, String configFile, MachineType peerType) {
        // Neither path reaches here with a Device-held key: create always mints the keypair itself, and
        // a reissue of an enrolled peer is refused before anything is rendered.
        Set<PeerArtifact> artefacts = PeerArtifact.forPeerType(peerType);

        String qrCodePngBase64 = artefacts.contains(PeerArtifact.QR_CODE)
            ? tryEncodeQrCodeBase64(configFile, name)
            : null;
        String dockerCompose = artefacts.contains(PeerArtifact.DOCKER_COMPOSE)
            ? generateDockerComposeUseCase.generateWireguardClientDockerCompose(
                id, defaultServerUrl(), ServiceNames.DEFAULT_WG_PORT)
            : null;
        String setupScript = artefacts.contains(PeerArtifact.SETUP_SCRIPT)
            ? generatePeerSetupScriptUseCase.generateSetupScript(
                id, defaultServerUrl(), ServiceNames.DEFAULT_WG_PORT).orElse(null)
            : null;
        // Mint a single-use setup token alongside the script so create AND reissue hand out a fresh
        // curl-able one-liner (Slice 4b). Only when the peer type actually has a setup script — the
        // token authorizes exactly the anonymous /vpn/peers/{id}/setup route that serves it.
        String setupToken = setupScript != null
            ? forVendingSetupTokens.issue(id).value()
            : null;

        return new CreatePeerResponse(
                id, machineId == null ? null : machineId.value(), name, ipAddress, publicKey,
                configFile, peerType.name(),
                artefacts.stream().map(Enum::name).sorted().toList(),
                qrCodePngBase64, dockerCompose, setupScript, setupToken);
    }

    @PatchMapping("/{peerId}")
    public ResponseEntity<Void> renamePeer(
            @PathVariable String peerId,
            @RequestBody(required = false) RenamePeerRequest request) {
        String newName = request != null ? request.newName() : null;
        log.info("Renaming peer {} to {}", LogSafe.forLog(peerId), LogSafe.forLog(newName));
        renamePeerUseCase.renamePeer(peerId, newName);
        forPublishingEvents.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{peerIdentifier}")
    public ResponseEntity<Void> deletePeer(@PathVariable String peerIdentifier) {
        log.info("Deleting VPN peer: {}", LogSafe.forLog(peerIdentifier));
        deletePeerUseCase.deletePeer(peerIdentifier);
        forPublishingEvents.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{peerId}/lan-address")
    public ResponseEntity<Void> updateLanAddress(
            @PathVariable String peerId,
            @RequestBody(required = false) UpdateLanAddressRequest request) {
        String lanAddress = request != null ? request.lanAddress() : null;
        LanAddress.validate(lanAddress);
        log.info("Updating LAN address for peer {} to {}",
            LogSafe.forLog(peerId), LogSafe.forLog(lanAddress));
        forUpdatingPeerConfigurations.updateLanAddress(peerId, lanAddress);
        forPublishingEvents.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{peerId}/lan-cidr")
    public ResponseEntity<Void> updateLanCidr(
            @PathVariable String peerId,
            @RequestBody(required = false) UpdateLanCidrRequest request) {
        String lanCidr = request != null ? request.lanCidr() : null;
        log.info("Updating LAN CIDR for peer {} to {}",
            LogSafe.forLog(peerId), LogSafe.forLog(lanCidr));
        // updateLanCidr signals a CIDR-already-owned conflict via ConflictException -> 409.
        updateLanCidrUseCase.updateLanCidr(peerId, lanCidr);
        forPublishingEvents.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{peerId}/description")
    public ResponseEntity<Void> updateDescription(
            @PathVariable String peerId,
            @RequestBody(required = false) UpdateDescriptionRequest request) {
        String description = request != null ? request.description() : null;
        log.info("Updating description for peer {}", LogSafe.forLog(peerId));
        forUpdatingPeerConfigurations.updateDescription(peerId, description);
        forPublishingEvents.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.noContent().build();
    }

    /**
     * Sets (or, with a blank/null value, clears) the peer's device-category override — the icon
     * hint, orthogonal to the routing {@code peerType}. An invalid category value propagates as
     * {@code IllegalArgumentException} -> 400; an unknown peer as {@code PeerNotFoundException} -> 404.
     */
    @PatchMapping("/{peerId}/device-category")
    public ResponseEntity<Void> updateDeviceCategory(
            @PathVariable String peerId,
            @RequestBody(required = false) UpdateDeviceCategoryRequest request) {
        String deviceCategory = request != null ? request.deviceCategory() : null;
        log.info("Updating device category for peer {} to {}",
            LogSafe.forLog(peerId), LogSafe.forLog(deviceCategory));
        updatePeerDeviceCategoryUseCase.updatePeerDeviceCategory(peerId, deviceCategory);
        forPublishingEvents.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{peerIdentifier}/config")
    public ResponseEntity<?> getPeerConfig(@PathVariable String peerIdentifier) {
        log.info("Fetching config for peer: {}", LogSafe.forLog(peerIdentifier));

        // /config accepts a name OR an IP. The marker is keyed by peer id (= dir name), so
        // we resolve via getPeerConfigUseCase first to map IP→id, then atomically mark.
        var config = getPeerConfigUseCase.getPeerConfig(peerIdentifier);
        if (config.isEmpty()) {
            log.warn("Peer config not found for identifier: {}", LogSafe.forLog(peerIdentifier));
            return ResponseEntity.notFound().build();
        }
        if (!forTrackingPeerConfigRetrieval.markViewedIfNotAlready(config.get().id())) {
            return alreadyViewedResponse();
        }
        var result = config.get();
        PeerConfigResponse response = new PeerConfigResponse(
                result.id(),
                result.name(),
                result.ipAddress(),
                result.configContent(),
                result.peerType() != null ? result.peerType().name() : null,
                PeerArtifact.forPeer(result.peerType(), result.deviceHeldKey()).stream()
                    .map(Enum::name).sorted().toList()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{peerId}/config-file")
    public ResponseEntity<?> downloadConfigFile(@PathVariable String peerId) {
        log.info("Downloading config file for peer: {}", LogSafe.forLog(peerId));
        ResponseEntity<?> gate = checkOneShotGate(peerId);
        if (gate != null) return gate;
        return getPeerConfigUseCase.getPeerConfig(peerId)
                .map(result -> {
                    byte[] content = result.configContent().getBytes();
                    ByteArrayResource resource = new ByteArrayResource(content);
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=" + peerId + ".conf")
                            .contentType(MediaType.parseMediaType("text/plain"))
                            .contentLength(content.length)
                            .<Object>body(resource);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{peerId}/qr-code")
    public ResponseEntity<?> getPeerQrCode(@PathVariable String peerId) {
        log.info("Generating QR code for peer: {}", LogSafe.forLog(peerId));
        ResponseEntity<?> gate = checkOneShotGate(peerId);
        if (gate != null) return gate;
        var config = getPeerConfigUseCase.getPeerConfig(peerId);
        if (config.isEmpty()) return ResponseEntity.notFound().build();
        try {
            byte[] png = encodeQrCodePng(config.get().configContent());
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(png);
        } catch (Exception e) {
            log.error("Failed to generate QR code for peer {}: {}", LogSafe.forLog(peerId), e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{peerId}/docker-compose")
    public ResponseEntity<?> downloadDockerCompose(
            @PathVariable String peerId,
            @RequestParam(required = false, defaultValue = "vaier.eilertsen.family") String serverUrl,
            @RequestParam(required = false, defaultValue = ServiceNames.DEFAULT_WG_PORT) String serverPort
    ) {
        log.info("Generating docker-compose for peer: {}", LogSafe.forLog(peerId));
        ResponseEntity<?> gate = checkOneShotGate(peerId);
        if (gate != null) return gate;

        String dockerCompose = generateDockerComposeUseCase.generateWireguardClientDockerCompose(
                peerId, serverUrl, serverPort);
        byte[] content = dockerCompose.getBytes();

        ByteArrayResource resource = new ByteArrayResource(content);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=docker-compose.yml")
                .contentType(MediaType.parseMediaType("application/x-yaml"))
                .contentLength(content.length)
                .body(resource);
    }

    @GetMapping("/{peerId}/setup-script")
    public ResponseEntity<?> downloadSetupScript(
            @PathVariable String peerId,
            @RequestParam(required = false, defaultValue = "vaier.eilertsen.family") String serverUrl,
            @RequestParam(required = false, defaultValue = ServiceNames.DEFAULT_WG_PORT) String serverPort
    ) {
        log.info("Generating setup script for peer: {}", LogSafe.forLog(peerId));
        ResponseEntity<?> gate = checkOneShotGate(peerId);
        if (gate != null) return gate;

        return generatePeerSetupScriptUseCase.generateSetupScript(peerId, serverUrl, serverPort)
                .map(script -> {
                    byte[] content = script.getBytes();
                    ByteArrayResource resource = new ByteArrayResource(content);
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=setup-" + peerId + ".sh")
                            .contentType(MediaType.parseMediaType("application/x-sh"))
                            .contentLength(content.length)
                            .<Object>body(resource);
                })
                .orElseGet(() -> {
                    log.warn("Peer not found for setup script: {}", LogSafe.forLog(peerId));
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * ANONYMOUS, token-gated setup download (Slice 4b). Reached via a surgical Traefik forward-auth
     * exemption for this ONE path — a bare box being onboarded has no oauth2 session, so the
     * single-use {@link net.vaier.domain.SetupToken} is the only authorization, validated here in
     * Vaier. The token is per-peer, ~15-min TTL, and burned on first use; it also burns the one-shot
     * config-retrieval budget (#202), so an intercepted-and-spent link leaves the box unable to come
     * up and the operator regenerates. Serves the script as {@code text/plain} so {@code curl … | sh}
     * works. NEVER log the token.
     *
     * <p>Ordering is deliberate: consume the token FIRST (single-use — a used link is spent even if
     * the config budget later 410s), then the one-shot gate, then generate. A failed token check
     * serves nothing.
     */
    @GetMapping("/{peerId}/setup")
    public ResponseEntity<?> downloadTokenizedSetupScript(
            @PathVariable String peerId,
            @RequestParam(name = "t", required = false) String token) {
        log.info("Tokenized setup script requested for peer: {}", LogSafe.forLog(peerId));
        if (token == null || !forVendingSetupTokens.consume(peerId, token)) {
            log.warn("Rejected tokenized setup for peer {}: missing, invalid, or already-used token",
                LogSafe.forLog(peerId));
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.TEXT_PLAIN)
                .body("This setup link is invalid or has already been used. Regenerate it in Vaier.\n");
        }
        ResponseEntity<?> gate = checkOneShotGate(peerId);
        if (gate != null) return gate;

        return generatePeerSetupScriptUseCase.generateSetupScript(
                    peerId, defaultServerUrl(), ServiceNames.DEFAULT_WG_PORT)
                .<ResponseEntity<?>>map(script -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(script))
                .orElseGet(() -> {
                    log.warn("Peer not found for tokenized setup script: {}", LogSafe.forLog(peerId));
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * One-shot gate (#202). Atomically marks the peer as viewed; returns a 404 if the peer
     * directory doesn't exist, or a 410 if the marker was already set. Returns null on first
     * successful view — the caller proceeds to serve the artefact.
     */
    private ResponseEntity<?> checkOneShotGate(String peerId) {
        try {
            if (!forTrackingPeerConfigRetrieval.markViewedIfNotAlready(peerId)) {
                return alreadyViewedResponse();
            }
            return null;
        } catch (IllegalStateException e) {
            log.warn("Peer not found for one-shot gate: {}", LogSafe.forLog(peerId));
            return ResponseEntity.notFound().build();
        }
    }

    private static ResponseEntity<?> alreadyViewedResponse() {
        return ResponseEntity.status(org.springframework.http.HttpStatus.GONE)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ALREADY_VIEWED_BODY);
    }

    /**
     * Best-effort QR PNG → base64. Returns null and logs on failure so the create response is
     * still usable (config text is still inline; the operator can copy/paste).
     */
    private String tryEncodeQrCodeBase64(String content, String peerId) {
        try {
            return java.util.Base64.getEncoder().encodeToString(encodeQrCodePng(content));
        } catch (Exception e) {
            log.error("Failed to generate QR code for peer {}: {}", LogSafe.forLog(peerId), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Server URL used to seed the inline docker-compose / setup-script in the create response.
     * The GET endpoints accept this as a query param; the create flow has no such param, so we
     * fall back to {@code VAIER_DOMAIN}-derived {@code vaier.<domain>} (the canonical WireGuard
     * endpoint for the stack).
     */
    private String defaultServerUrl() {
        return new net.vaier.domain.VaierHostnames(configResolver.getDomain()).vaierServerFqdn();
    }

    private static byte[] encodeQrCodePng(String content) throws Exception {
        com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
        com.google.zxing.common.BitMatrix matrix = writer.encode(
                content,
                com.google.zxing.BarcodeFormat.QR_CODE,
                256, 256,
                java.util.Map.of(
                        com.google.zxing.EncodeHintType.ERROR_CORRECTION,
                        com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
                        com.google.zxing.EncodeHintType.MARGIN, 2
                )
        );
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(
                com.google.zxing.client.j2se.MatrixToImageWriter.toBufferedImage(matrix),
                "PNG", out);
        return out.toByteArray();
    }

    /**
     * @param id                 the peer's immutable identifier — config directory name, REST path segment.
     * @param name               the operator-facing display label.
     * @param tunnelIp           the WireGuard tunnel IP pre-extracted from {@code allowedIps}.
     * @param connected          server-computed connectivity per the domain rule {@code VpnClient.isConnected()}.
     * @param isServer / isClient / isRelay  role flags from the domain — UI doesn't enum-match.
     * @param availableArtifacts the {@code PeerArtifact} names this peer supports for download.
     */
    public record VpnPeerResponse(
            String id,
            /** The machine's identity — what the browser joins this list to {@code /machines} on. */
            String machineId,
            String name,
            String publicKey,
            String allowedIps,
            String tunnelIp,
            String endpointIp,
            String endpointPort,
            String latestHandshake,
            boolean connected,
            String transferRx,
            String transferTx,
            String peerType,
            boolean isServer,
            boolean isClient,
            boolean isRelay,
            List<String> availableArtifacts,
            /** True when the peer minted its own keypair: nothing here can reissue or regenerate it. */
            boolean deviceHeldKey,
            String lanCidr,
            String lanAddress,
            String description,
            Double latitude,
            Double longitude,
            String city,
            String country,
            boolean configOutOfDate,
            String deviceCategory,
            boolean deviceCategoryOverridden,
            boolean sshAccess,
            /** Where to draw this machine, or null when Vaier has no honest answer. */
            PlacementResponse placement,
            /** Where this machine has been, oldest first; empty when it has never reported. */
            List<TrailPointResponse> positionTrail,
            /** What this machine last opened, or null when Vaier has never seen it reach anything. */
            LastServiceReachedResponse lastServiceReached
    ) {}

    /**
     * @param host        the gated host the machine reached.
     * @param displayName the launchpad's label for it, or null when Vaier publishes no route for that host.
     * @param at          when it was reached.
     */
    public record LastServiceReachedResponse(
            String host,
            String displayName,
            Instant at
    ) {}

    /**
     * @param source         {@code REPORTED} or {@code ISP_ESTIMATE}.
     * @param asOf           when that evidence was taken.
     * @param accuracyMetres null for an ISP estimate.
     * @param place          "city, country" when known; null for a reported position (no reverse geocoding).
     */
    public record PlacementResponse(
            double latitude,
            double longitude,
            String source,
            Instant asOf,
            Double accuracyMetres,
            boolean stale,
            String place
    ) {}

    /**
     * One point of a machine's position trail — a reported position that earned a place in it.
     *
     * @param accuracyMetres the radius the browser gave, or null when it gave none.
     * @param at             when the point was measured.
     */
    public record TrailPointResponse(
            double latitude,
            double longitude,
            Double accuracyMetres,
            Instant at
    ) {}

    /**
     * Coordinates only. Nothing here can name a machine, and nothing may be added that could: which device
     * is reporting comes from the tunnel or the device claim, never from the caller's say-so.
     */
    public record ReportMyPositionRequest(
            Double latitude,
            Double longitude,
            Double accuracyMetres
    ) {}

    /** The machine this browser has claimed, or null when it holds no (unrevoked) claim. */
    public record MyDeviceResponse(
            String machineId
    ) {}

    /**
     * The intent-first "add a machine" flow sends {@code intent} + {@code windows} instead of a raw
     * {@code peerType}: the operator says what a machine is <em>for</em> and whether it runs Windows,
     * and the intent -> {@link MachineType} decision is the domain's ({@link MachineIntent}).
     * The legacy {@code peerType} field is still honoured when no {@code intent} is given, so existing
     * callers keep working; {@code intent} takes precedence when both are present.
     */
    public record CreatePeerRequest(
            String name,
            MachineType peerType,
            String lanCidr,
            String lanAddress,
            String description,
            MachineIntent intent,
            Boolean windows
    ) {}

    public record CreatePeerResponse(
            String id,
            String machineId,
            String name,
            String ipAddress,
            String publicKey,
            String configFile,
            String peerType,
            List<String> availableArtifacts,
            String qrCodePngBase64,
            String dockerCompose,
            String setupScript,
            String setupToken
    ) {}

    /**
     * An {@code Enrolment} request: the device's chosen label and the public half of the keypair it
     * minted itself. There is no peer type to choose — the Vaier app runs on a phone.
     */
    public record EnrolDeviceRequest(
            String name,
            String publicKey
    ) {}

    /**
     * What the app gets back. No key of any kind: {@code configFile} is the installable WireGuard config
     * with its {@code PrivateKey} line absent, because the device already holds that half.
     */
    public record EnrolDeviceResponse(
            String id,
            String machineId,
            String name,
            String ipAddress,
            String configFile
    ) {}

    /**
     * What a phone presents to {@code leave} the fleet: its own public key, and the preshared key it
     * was handed at approval — the half of its config that only it and Vaier hold.
     */
    public record LeaveFleetRequest(
            String publicKey,
            String presharedKey
    ) {}

    /** The same proof as leaving; presented by a phone that only wants to know if it still belongs. */
    public record StandingRequest(String publicKey, String presharedKey) {}

    public record UpdateLanAddressRequest(
            String lanAddress
    ) {}

    public record UpdateLanCidrRequest(
            String lanCidr
    ) {}

    public record UpdateDescriptionRequest(
            String description
    ) {}

    public record RenamePeerRequest(
            String newName
    ) {}

    public record UpdateDeviceCategoryRequest(
            String deviceCategory
    ) {}

    public record PeerConfigResponse(
            String id,
            String name,
            String ipAddress,
            String configFile,
            String peerType,
            List<String> availableArtifacts
    ) {}

    public record ServerLocationResponse(
            String publicHost,
            Double latitude,
            Double longitude,
            String city,
            String country,
            String lanCidr
    ) {}

}
