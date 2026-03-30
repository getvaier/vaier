package net.vaier.rest;

import net.vaier.application.CreatePeerUseCase;
import net.vaier.application.DeletePeerUseCase;
import net.vaier.application.GenerateDockerComposeUseCase;
import net.vaier.application.GeneratePeerSetupScriptUseCase;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForFetchingPeerMetrics;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForResolvingPeerNames;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/vpn/peers")
@RequiredArgsConstructor
@Slf4j
public class VpnPeerRestController {

    private final ForGettingVpnClients vpnClientService;
    private final ForResolvingPeerNames peerNameResolver;
    private final ForFetchingPeerMetrics forFetchingPeerMetrics;
    private final CreatePeerUseCase createPeerUseCase;
    private final DeletePeerUseCase deletePeerUseCase;
    private final GetPeerConfigUseCase getPeerConfigUseCase;
    private final GenerateDockerComposeUseCase generateDockerComposeUseCase;
    private final GeneratePeerSetupScriptUseCase generatePeerSetupScriptUseCase;

    @GetMapping
    public ResponseEntity<List<VpnPeerResponse>> listPeers() {
        log.info("Fetching all VPN peers");
        try {
            List<VpnClient> clients = vpnClientService.getClients();
            List<VpnPeerResponse> response = clients.stream()
                    .map(client -> {
                        String peerName = peerNameResolver.resolvePeerNameByIp(client.allowedIps().split("/")[0]);
                        return new VpnPeerResponse(
                                peerName,
                                client.publicKey(),
                                client.allowedIps(),
                                client.endpointIp(),
                                client.endpointPort(),
                                client.latestHandshake(),
                                client.transferRx(),
                                client.transferTx()
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

    @PostMapping
    public ResponseEntity<CreatePeerResponse> createPeer(@RequestBody CreatePeerRequest request) {
        log.info("Creating new VPN peer: {}", request.name());

        String interfaceName = "wg0"; // Default WireGuard interface
        boolean routeAllTraffic = request.routeAllTraffic() != null ? request.routeAllTraffic() : true;

        CreatePeerUseCase.CreatedPeerUco createdPeer = createPeerUseCase.createPeer(
                interfaceName,
                request.name(),
                routeAllTraffic
        );

        CreatePeerResponse response = new CreatePeerResponse(
                createdPeer.name(),
                createdPeer.ipAddress(),
                createdPeer.publicKey(),
                createdPeer.clientConfigFile()
        );

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{peerIdentifier}")
    public ResponseEntity<Void> deletePeer(@PathVariable String peerIdentifier) {
        log.info("Deleting VPN peer: {}", peerIdentifier);

        try {
            String interfaceName = "wg0"; // Default WireGuard interface
            deletePeerUseCase.deletePeer(interfaceName, peerIdentifier);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.error("Peer not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to delete peer: {}", e.getMessage(), e);
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
                            result.configContent()
                    );
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    log.warn("Peer config not found for identifier: {}", peerIdentifier);
                    return ResponseEntity.notFound().build();
                });
    }

    @GetMapping("/{peerName}/docker-compose")
    public ResponseEntity<Resource> downloadDockerCompose(
            @PathVariable String peerName,
            @RequestParam(required = false, defaultValue = "vaier.eilertsen.family") String serverUrl,
            @RequestParam(required = false, defaultValue = "51820") String serverPort
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
            @RequestParam(required = false, defaultValue = "51820") String serverPort
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
            String transferTx
    ) {}

    public record CreatePeerRequest(
            String name,
            Boolean routeAllTraffic
    ) {}

    public record CreatePeerResponse(
            String name,
            String ipAddress,
            String publicKey,
            String configFile
    ) {}

    public record PeerConfigResponse(
            String name,
            String ipAddress,
            String configFile
    ) {}

    @GetMapping("/{peerName}/netdata")
    public ResponseEntity<PeerMetricsResponse> getPeerMetrics(@PathVariable String peerName) {
        log.info("Fetching Netdata metrics for peer: {}", peerName);
        try {
            String vpnIp = vpnClientService.getClients().stream()
                    .filter(c -> peerName.equals(peerNameResolver.resolvePeerNameByIp(c.allowedIps().split("/")[0])))
                    .map(c -> c.allowedIps().split("/")[0])
                    .findFirst()
                    .orElse(null);

            if (vpnIp == null) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Map<String, Double>> charts = forFetchingPeerMetrics.fetchMetrics(vpnIp);
            return ResponseEntity.ok(new PeerMetricsResponse(true, charts));
        } catch (Exception e) {
            log.error("Failed to fetch Netdata metrics for peer {}: {}", peerName, e.getMessage());
            return ResponseEntity.ok(new PeerMetricsResponse(false, Map.of()));
        }
    }

    public record PeerMetricsResponse(
            boolean available,
            Map<String, Map<String, Double>> charts
    ) {}
}
