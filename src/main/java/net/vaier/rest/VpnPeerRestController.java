package net.vaier.rest;

import net.vaier.application.CreatePeerUseCase;
import net.vaier.application.DeletePeerUseCase;
import net.vaier.application.GenerateDockerComposeUseCase;
import net.vaier.application.GeneratePeerSetupScriptUseCase;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetServerLocationUseCase;
import net.vaier.application.GetVpnPeersUseCase;
import net.vaier.application.GetVpnPeersUseCase.VpnPeerView;
import net.vaier.application.RenamePeerUseCase;
import net.vaier.application.UpdateLanCidrUseCase;
import net.vaier.adapter.driven.SseEventPublisher;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.port.ForTrackingPeerConfigRetrieval;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import net.vaier.config.ServiceNames;
import net.vaier.domain.MachineType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/vpn/peers")
@RequiredArgsConstructor
@Slf4j
public class VpnPeerRestController {

    private final GetVpnPeersUseCase getVpnPeersUseCase;
    private final GetPeerConfigUseCase getPeerConfigUseCase;
    private final CreatePeerUseCase createPeerUseCase;
    private final DeletePeerUseCase deletePeerUseCase;
    private final GenerateDockerComposeUseCase generateDockerComposeUseCase;
    private final GeneratePeerSetupScriptUseCase generatePeerSetupScriptUseCase;
    private final UpdateLanCidrUseCase updateLanCidrUseCase;
    private final RenamePeerUseCase renamePeerUseCase;
    private final ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    private final ForTrackingPeerConfigRetrieval forTrackingPeerConfigRetrieval;
    private final SseEventPublisher sseEventPublisher;
    private final GetServerLocationUseCase getServerLocationUseCase;
    private final ConfigResolver configResolver;

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
        return sseEventPublisher.subscribe("vpn-peers");
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
            v.id(), v.name(), v.publicKey(), v.allowedIps(), v.tunnelIp(),
            v.endpointIp(), v.endpointPort(), v.latestHandshake(),
            v.connected(), v.transferRx(), v.transferTx(),
            v.peerType().name(), v.isServer(), v.isClient(), v.isRelay(),
            v.availableArtifacts().stream().map(Enum::name).sorted().toList(),
            v.lanCidr(), v.lanAddress(), v.description(),
            v.geoLocation().map(GeoLocation::latitude).orElse(null),
            v.geoLocation().map(GeoLocation::longitude).orElse(null),
            v.geoLocation().map(GeoLocation::city).orElse(null),
            v.geoLocation().map(GeoLocation::country).orElse(null));
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

    @PostMapping
    public ResponseEntity<CreatePeerResponse> createPeer(@RequestBody CreatePeerRequest request) {
        log.info("Creating new VPN peer: {}", request.name());

        CreatePeerUseCase.CreatedPeerUco createdPeer = createPeerUseCase.createPeer(
                request.name(),
                request.peerType(),
                request.lanCidr(),
                request.lanAddress(),
                request.description()
        );

        // Inline every artefact so the create-success modal renders config + QR + download buttons
        // in one response, without follow-up GETs. The five GET endpoints are gated by a one-shot
        // marker (#202); the marker is set on first GET, NOT on create. The UI uses only the
        // inline payload so it never burns the budget; a raw curl GET can still recover any one
        // artefact once (then 410 forever).
        java.util.Set<net.vaier.domain.PeerArtifact> artefacts =
            net.vaier.domain.PeerArtifact.forPeerType(createdPeer.peerType());

        String qrCodePngBase64 = artefacts.contains(net.vaier.domain.PeerArtifact.QR_CODE)
            ? tryEncodeQrCodeBase64(createdPeer.clientConfigFile(), createdPeer.name())
            : null;
        String dockerCompose = artefacts.contains(net.vaier.domain.PeerArtifact.DOCKER_COMPOSE)
            ? generateDockerComposeUseCase.generateWireguardClientDockerCompose(
                createdPeer.id(), defaultServerUrl(), ServiceNames.DEFAULT_WG_PORT)
            : null;
        String setupScript = artefacts.contains(net.vaier.domain.PeerArtifact.SETUP_SCRIPT)
            ? generatePeerSetupScriptUseCase.generateSetupScript(
                createdPeer.id(), defaultServerUrl(), ServiceNames.DEFAULT_WG_PORT).orElse(null)
            : null;

        CreatePeerResponse response = new CreatePeerResponse(
                createdPeer.id(),
                createdPeer.name(),
                createdPeer.ipAddress(),
                createdPeer.publicKey(),
                createdPeer.clientConfigFile(),
                createdPeer.peerType().name(),
                artefacts.stream().map(Enum::name).sorted().toList(),
                qrCodePngBase64,
                dockerCompose,
                setupScript
        );

        sseEventPublisher.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{peerName}")
    public ResponseEntity<Void> renamePeer(
            @PathVariable String peerName,
            @RequestBody(required = false) RenamePeerRequest request) {
        String newName = request != null ? request.newName() : null;
        log.info("Renaming peer {} to {}", peerName, newName);
        try {
            renamePeerUseCase.renamePeer(peerName, newName);
            sseEventPublisher.publish("vpn-peers", "peers-updated", "");
            return ResponseEntity.noContent().build();
        } catch (net.vaier.domain.PeerNotFoundException e) {
            log.warn("Peer not found for rename: {}", peerName);
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Rename conflict for peer {}: {}", peerName, e.getMessage());
            return ResponseEntity.status(409).build();
        } catch (IllegalArgumentException e) {
            log.warn("Bad rename request for peer {}: {}", peerName, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to rename peer {}: {}", peerName, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/{peerIdentifier}")
    public ResponseEntity<Void> deletePeer(@PathVariable String peerIdentifier) {
        log.info("Deleting VPN peer: {}", peerIdentifier);

        try {
            deletePeerUseCase.deletePeer(peerIdentifier);
            sseEventPublisher.publish("vpn-peers", "peers-updated", "");
            return ResponseEntity.noContent().build();
        } catch (net.vaier.domain.PeerNotFoundException e) {
            log.error("Peer not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.warn("Bad request deleting peer {}: {}", peerIdentifier, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to delete peer: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PatchMapping("/{peerName}/lan-address")
    public ResponseEntity<Void> updateLanAddress(
            @PathVariable String peerName,
            @RequestBody(required = false) UpdateLanAddressRequest request) {
        String lanAddress = request != null ? request.lanAddress() : null;
        log.info("Updating LAN address for peer {} to {}", peerName, lanAddress);
        try {
            forUpdatingPeerConfigurations.updateLanAddress(peerName, lanAddress);
            sseEventPublisher.publish("vpn-peers", "peers-updated", "");
            return ResponseEntity.noContent().build();
        } catch (net.vaier.domain.PeerNotFoundException e) {
            log.warn("Peer not found for lan-address update: {}", peerName);
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.warn("Bad lan-address request for peer {}: {}", peerName, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to update lan address for peer {}: {}", peerName, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PatchMapping("/{peerName}/lan-cidr")
    public ResponseEntity<Void> updateLanCidr(
            @PathVariable String peerName,
            @RequestBody(required = false) UpdateLanCidrRequest request) {
        String lanCidr = request != null ? request.lanCidr() : null;
        log.info("Updating LAN CIDR for peer {} to {}", peerName, lanCidr);
        try {
            updateLanCidrUseCase.updateLanCidr(peerName, lanCidr);
            sseEventPublisher.publish("vpn-peers", "peers-updated", "");
            return ResponseEntity.noContent().build();
        } catch (net.vaier.domain.PeerNotFoundException e) {
            log.warn("Peer not found for lan-cidr update: {}", peerName);
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.warn("Bad lan-cidr request for peer {}: {}", peerName, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            log.warn("LAN CIDR conflict for peer {}: {}", peerName, e.getMessage());
            return ResponseEntity.status(409).build();
        } catch (Exception e) {
            log.error("Failed to update lan cidr for peer {}: {}", peerName, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PatchMapping("/{peerName}/description")
    public ResponseEntity<Void> updateDescription(
            @PathVariable String peerName,
            @RequestBody(required = false) UpdateDescriptionRequest request) {
        String description = request != null ? request.description() : null;
        log.info("Updating description for peer {}", peerName);
        try {
            forUpdatingPeerConfigurations.updateDescription(peerName, description);
            sseEventPublisher.publish("vpn-peers", "peers-updated", "");
            return ResponseEntity.noContent().build();
        } catch (net.vaier.domain.PeerNotFoundException e) {
            log.warn("Peer not found for description update: {}", peerName);
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.warn("Bad description request for peer {}: {}", peerName, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to update description for peer {}: {}", peerName, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{peerIdentifier}/config")
    public ResponseEntity<?> getPeerConfig(@PathVariable String peerIdentifier) {
        log.info("Fetching config for peer: {}", peerIdentifier);

        // /config accepts a name OR an IP. The marker is keyed by peer id (= dir name), so
        // we resolve via getPeerConfigUseCase first to map IP→id, then atomically mark.
        var config = getPeerConfigUseCase.getPeerConfig(peerIdentifier);
        if (config.isEmpty()) {
            log.warn("Peer config not found for identifier: {}", peerIdentifier);
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
                net.vaier.domain.PeerArtifact.forPeerType(result.peerType()).stream()
                    .map(Enum::name).sorted().toList()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{peerName}/config-file")
    public ResponseEntity<?> downloadConfigFile(@PathVariable String peerName) {
        log.info("Downloading config file for peer: {}", peerName);
        ResponseEntity<?> gate = checkOneShotGate(peerName);
        if (gate != null) return gate;
        return getPeerConfigUseCase.getPeerConfig(peerName)
                .map(result -> {
                    byte[] content = result.configContent().getBytes();
                    ByteArrayResource resource = new ByteArrayResource(content);
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=" + peerName + ".conf")
                            .contentType(MediaType.parseMediaType("text/plain"))
                            .contentLength(content.length)
                            .<Object>body(resource);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{peerName}/qr-code")
    public ResponseEntity<?> getPeerQrCode(@PathVariable String peerName) {
        log.info("Generating QR code for peer: {}", peerName);
        ResponseEntity<?> gate = checkOneShotGate(peerName);
        if (gate != null) return gate;
        var config = getPeerConfigUseCase.getPeerConfig(peerName);
        if (config.isEmpty()) return ResponseEntity.notFound().build();
        try {
            byte[] png = encodeQrCodePng(config.get().configContent());
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(png);
        } catch (Exception e) {
            log.error("Failed to generate QR code for peer {}: {}", peerName, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{peerName}/docker-compose")
    public ResponseEntity<?> downloadDockerCompose(
            @PathVariable String peerName,
            @RequestParam(required = false, defaultValue = "vaier.eilertsen.family") String serverUrl,
            @RequestParam(required = false, defaultValue = ServiceNames.DEFAULT_WG_PORT) String serverPort
    ) {
        log.info("Generating docker-compose for peer: {}", peerName);
        ResponseEntity<?> gate = checkOneShotGate(peerName);
        if (gate != null) return gate;

        String dockerCompose = generateDockerComposeUseCase.generateWireguardClientDockerCompose(
                peerName, serverUrl, serverPort);
        byte[] content = dockerCompose.getBytes();

        ByteArrayResource resource = new ByteArrayResource(content);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=docker-compose.yml")
                .contentType(MediaType.parseMediaType("application/x-yaml"))
                .contentLength(content.length)
                .body(resource);
    }

    @GetMapping("/{peerName}/setup-script")
    public ResponseEntity<?> downloadSetupScript(
            @PathVariable String peerName,
            @RequestParam(required = false, defaultValue = "vaier.eilertsen.family") String serverUrl,
            @RequestParam(required = false, defaultValue = ServiceNames.DEFAULT_WG_PORT) String serverPort
    ) {
        log.info("Generating setup script for peer: {}", peerName);
        ResponseEntity<?> gate = checkOneShotGate(peerName);
        if (gate != null) return gate;

        return generatePeerSetupScriptUseCase.generateSetupScript(peerName, serverUrl, serverPort)
                .map(script -> {
                    byte[] content = script.getBytes();
                    ByteArrayResource resource = new ByteArrayResource(content);
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=setup-" + peerName + ".sh")
                            .contentType(MediaType.parseMediaType("application/x-sh"))
                            .contentLength(content.length)
                            .<Object>body(resource);
                })
                .orElseGet(() -> {
                    log.warn("Peer not found for setup script: {}", peerName);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * One-shot gate (#202). Atomically marks the peer as viewed; returns a 404 if the peer
     * directory doesn't exist, or a 410 if the marker was already set. Returns null on first
     * successful view — the caller proceeds to serve the artefact.
     */
    private ResponseEntity<?> checkOneShotGate(String peerName) {
        try {
            if (!forTrackingPeerConfigRetrieval.markViewedIfNotAlready(peerName)) {
                return alreadyViewedResponse();
            }
            return null;
        } catch (IllegalStateException e) {
            log.warn("Peer not found for one-shot gate: {}", peerName);
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
    private String tryEncodeQrCodeBase64(String content, String peerName) {
        try {
            return java.util.Base64.getEncoder().encodeToString(encodeQrCodePng(content));
        } catch (Exception e) {
            log.error("Failed to generate QR code for peer {}: {}", peerName, e.getMessage(), e);
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
            String lanCidr,
            String lanAddress,
            String description,
            Double latitude,
            Double longitude,
            String city,
            String country
    ) {}

    public record CreatePeerRequest(
            String name,
            MachineType peerType,
            String lanCidr,
            String lanAddress,
            String description
    ) {}

    public record CreatePeerResponse(
            String id,
            String name,
            String ipAddress,
            String publicKey,
            String configFile,
            String peerType,
            List<String> availableArtifacts,
            String qrCodePngBase64,
            String dockerCompose,
            String setupScript
    ) {}

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
