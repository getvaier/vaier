package net.vaier.rest;

import net.vaier.application.CreatePeerUseCase;
import net.vaier.application.DeletePeerUseCase;
import net.vaier.application.GenerateDockerComposeUseCase;
import net.vaier.application.GeneratePeerSetupScriptUseCase;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetServerLocationUseCase;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.ResolveVpnPeerNameUseCase;
import net.vaier.application.UpdateLanCidrUseCase;
import net.vaier.adapter.driven.SseEventPublisher;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import net.vaier.config.ServiceNames;
import net.vaier.domain.MachineType;
import net.vaier.domain.VpnClient;

import java.util.Optional;

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

    private final GetVpnClientsUseCase vpnClientService;
    private final ResolveVpnPeerNameUseCase peerNameResolver;
    private final GetPeerConfigUseCase getPeerConfigUseCase;
    private final CreatePeerUseCase createPeerUseCase;
    private final DeletePeerUseCase deletePeerUseCase;
    private final GenerateDockerComposeUseCase generateDockerComposeUseCase;
    private final GeneratePeerSetupScriptUseCase generatePeerSetupScriptUseCase;
    private final UpdateLanCidrUseCase updateLanCidrUseCase;
    private final ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    private final SseEventPublisher sseEventPublisher;
    private final ForGeolocatingIps forGeolocatingIps;
    private final GetServerLocationUseCase getServerLocationUseCase;

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToEvents() {
        return sseEventPublisher.subscribe("vpn-peers");
    }

    @GetMapping
    public ResponseEntity<List<VpnPeerResponse>> listPeers() {
        log.info("Fetching all VPN peers");
        try {
            List<VpnClient> clients = vpnClientService.getClients();
            List<VpnPeerResponse> response = clients.stream()
                    .map(client -> {
                        String peerIp = client.allowedIps().split("/")[0];
                        String peerName = peerNameResolver.resolvePeerNameByIp(peerIp);
                        var cfg = getPeerConfigUseCase.getPeerConfigByIp(peerIp);
                        MachineType peerType = cfg
                                .map(GetPeerConfigUseCase.PeerConfigResult::peerType)
                                .orElse(MachineType.UBUNTU_SERVER);
                        String lanCidr = cfg.map(GetPeerConfigUseCase.PeerConfigResult::lanCidr).orElse(null);
                        String lanAddress = cfg.map(GetPeerConfigUseCase.PeerConfigResult::lanAddress).orElse(null);
                        Optional<GeoLocation> geo = (client.endpointIp() != null && !client.endpointIp().isBlank())
                            ? forGeolocatingIps.locate(client.endpointIp())
                            : Optional.empty();
                        return new VpnPeerResponse(
                                peerName,
                                client.publicKey(),
                                client.allowedIps(),
                                client.endpointIp(),
                                client.endpointPort(),
                                client.latestHandshake(),
                                client.transferRx(),
                                client.transferTx(),
                                peerType.name(),
                                lanCidr,
                                lanAddress,
                                geo.map(GeoLocation::latitude).orElse(null),
                                geo.map(GeoLocation::longitude).orElse(null),
                                geo.map(GeoLocation::city).orElse(null),
                                geo.map(GeoLocation::country).orElse(null)
                        );
                    })
                    .toList();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to fetch VPN peers: {}", e.getMessage(), e);
            // Return empty list instead of error to prevent constant error messages
            return ResponseEntity.ok(List.of());
        }
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
                    loc.country()
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

        MachineType peerType = request.peerType() != null ? request.peerType() : MachineType.UBUNTU_SERVER;

        CreatePeerUseCase.CreatedPeerUco createdPeer = createPeerUseCase.createPeer(
                request.name(),
                peerType,
                request.lanCidr(),
                request.lanAddress()
        );

        CreatePeerResponse response = new CreatePeerResponse(
                createdPeer.name(),
                createdPeer.ipAddress(),
                createdPeer.publicKey(),
                createdPeer.clientConfigFile(),
                createdPeer.peerType().name()
        );

        sseEventPublisher.publish("vpn-peers", "peers-updated", "");
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{peerIdentifier}")
    public ResponseEntity<Void> deletePeer(@PathVariable String peerIdentifier) {
        log.info("Deleting VPN peer: {}", peerIdentifier);

        try {
            deletePeerUseCase.deletePeer(peerIdentifier);
            sseEventPublisher.publish("vpn-peers", "peers-updated", "");
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.error("Peer not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
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
        } catch (IllegalArgumentException e) {
            log.warn("Peer not found for lan-address update: {}", peerName);
            return ResponseEntity.notFound().build();
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
        } catch (IllegalArgumentException e) {
            log.warn("Peer not found for lan-cidr update: {}", peerName);
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("LAN CIDR conflict for peer {}: {}", peerName, e.getMessage());
            return ResponseEntity.status(409).build();
        } catch (Exception e) {
            log.error("Failed to update lan cidr for peer {}: {}", peerName, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{peerIdentifier}/config")
    public ResponseEntity<PeerConfigResponse> getPeerConfig(@PathVariable String peerIdentifier) {
        log.info("Fetching config for peer: {}", peerIdentifier);

        return getPeerConfigUseCase.getPeerConfig(peerIdentifier)
                .map(result -> {
                    PeerConfigResponse response = new PeerConfigResponse(
                            result.name(),
                            result.ipAddress(),
                            result.configContent(),
                            result.peerType() != null ? result.peerType().name() : null
                    );
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    log.warn("Peer config not found for identifier: {}", peerIdentifier);
                    return ResponseEntity.notFound().build();
                });
    }

    @GetMapping("/{peerName}/config-file")
    public ResponseEntity<Resource> downloadConfigFile(@PathVariable String peerName) {
        log.info("Downloading config file for peer: {}", peerName);
        return getPeerConfigUseCase.getPeerConfig(peerName)
                .map(result -> {
                    byte[] content = result.configContent().getBytes();
                    ByteArrayResource resource = new ByteArrayResource(content);
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=" + peerName + ".conf")
                            .contentType(MediaType.parseMediaType("text/plain"))
                            .contentLength(content.length)
                            .<Resource>body(resource);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{peerName}/qr-code")
    public ResponseEntity<byte[]> getPeerQrCode(@PathVariable String peerName) {
        log.info("Generating QR code for peer: {}", peerName);
        var config = getPeerConfigUseCase.getPeerConfig(peerName);
        if (config.isEmpty()) return ResponseEntity.notFound().build();
        try {
            com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
            com.google.zxing.common.BitMatrix matrix = writer.encode(
                    config.get().configContent(),
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
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(out.toByteArray());
        } catch (Exception e) {
            log.error("Failed to generate QR code for peer {}: {}", peerName, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{peerName}/docker-compose")
    public ResponseEntity<Resource> downloadDockerCompose(
            @PathVariable String peerName,
            @RequestParam(required = false, defaultValue = "vaier.eilertsen.family") String serverUrl,
            @RequestParam(required = false, defaultValue = ServiceNames.DEFAULT_WG_PORT) String serverPort
    ) {
        log.info("Generating docker-compose for peer: {}", peerName);

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
    public ResponseEntity<Resource> downloadSetupScript(
            @PathVariable String peerName,
            @RequestParam(required = false, defaultValue = "vaier.eilertsen.family") String serverUrl,
            @RequestParam(required = false, defaultValue = ServiceNames.DEFAULT_WG_PORT) String serverPort
    ) {
        log.info("Generating setup script for peer: {}", peerName);

        return generatePeerSetupScriptUseCase.generateSetupScript(peerName, serverUrl, serverPort)
                .map(script -> {
                    byte[] content = script.getBytes();
                    ByteArrayResource resource = new ByteArrayResource(content);
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=setup-" + peerName + ".sh")
                            .contentType(MediaType.parseMediaType("application/x-sh"))
                            .contentLength(content.length)
                            .<Resource>body(resource);
                })
                .orElseGet(() -> {
                    log.warn("Peer not found for setup script: {}", peerName);
                    return ResponseEntity.notFound().build();
                });
    }

    public record VpnPeerResponse(
            String name,
            String publicKey,
            String allowedIps,
            String endpointIp,
            String endpointPort,
            String latestHandshake,
            String transferRx,
            String transferTx,
            String peerType,
            String lanCidr,
            String lanAddress,
            Double latitude,
            Double longitude,
            String city,
            String country
    ) {}

    public record CreatePeerRequest(
            String name,
            MachineType peerType,
            String lanCidr,
            String lanAddress
    ) {}

    public record CreatePeerResponse(
            String name,
            String ipAddress,
            String publicKey,
            String configFile,
            String peerType
    ) {}

    public record UpdateLanAddressRequest(
            String lanAddress
    ) {}

    public record UpdateLanCidrRequest(
            String lanCidr
    ) {}

    public record PeerConfigResponse(
            String name,
            String ipAddress,
            String configFile,
            String peerType
    ) {}

    public record ServerLocationResponse(
            String publicHost,
            double latitude,
            double longitude,
            String city,
            String country
    ) {}

}
