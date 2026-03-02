package net.vaier.rest;

import net.vaier.application.CreatePeerUseCase;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForRestartingContainers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.service.VpnService;
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
    private final CreatePeerUseCase createPeerUseCase;
    private final ForRestartingContainers containerRestarter;

    @org.springframework.beans.factory.annotation.Value("${wireguard.config.path:/wireguard/config}")
    private String wireguardConfigPath;

    @GetMapping
    public ResponseEntity<List<VpnPeerResponse>> listPeers() {
        log.info("Fetching all VPN peers");
        try {
            List<VpnClient> clients = vpnClientService.getClients();
            List<VpnPeerResponse> response = clients.stream()
                    .map(client -> {
                        String peerName = findPeerNameByIp(client.allowedIps().split("/")[0]);
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

    private String findPeerNameByIp(String ipAddress) {
        try {
            java.nio.file.Path configDir = java.nio.file.Paths.get(wireguardConfigPath);
            log.debug("Searching for peer with IP {} in directory: {}", ipAddress, configDir.toAbsolutePath());

            if (!java.nio.file.Files.exists(configDir)) {
                log.warn("Config directory does not exist: {}", configDir.toAbsolutePath());
                return ipAddress;
            }

            try (var stream = java.nio.file.Files.list(configDir)) {
                return stream
                        .filter(java.nio.file.Files::isDirectory)
                        .filter(dir -> {
                            try {
                                String dirName = dir.getFileName().toString();
                                // Skip non-peer directories (like wg_confs)
                                if (dirName.equals("wg_confs") || dirName.startsWith(".")) {
                                    return false;
                                }

                                java.nio.file.Path confFile = dir.resolve(dirName + ".conf");
                                log.debug("Checking config file: {}", confFile);

                                if (java.nio.file.Files.exists(confFile)) {
                                    String content = java.nio.file.Files.readString(confFile);
                                    for (String line : content.split("\n")) {
                                        if (line.trim().startsWith("Address")) {
                                            String address = line.substring(line.indexOf('=') + 1).trim();
                                            String ip = address.split("/")[0];
                                            log.debug("Found IP {} in peer {}", ip, dirName);
                                            if (ip.equals(ipAddress)) {
                                                log.info("Matched peer {} for IP {}", dirName, ipAddress);
                                                return true;
                                            }
                                        }
                                    }
                                } else {
                                    log.debug("Config file does not exist: {}", confFile);
                                }
                            } catch (Exception e) {
                                log.warn("Error checking peer dir {}: {}", dir, e.getMessage());
                            }
                            return false;
                        })
                        .map(path -> path.getFileName().toString())
                        .findFirst()
                        .orElseGet(() -> {
                            log.warn("No peer directory found for IP: {}", ipAddress);
                            return ipAddress;
                        });
            }
        } catch (Exception e) {
            log.error("Error finding peer name for IP {}: {}", ipAddress, e.getMessage(), e);
            return ipAddress; // Fallback to IP
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

    @PostMapping("/setup-nat")
    public ResponseEntity<String> setupNat() {
        log.info("Manually setting up NAT rules");
        try {
            if (createPeerUseCase instanceof VpnService vpnService) {
                vpnService.ensureNatRulesActive();
                containerRestarter.restartContainer("wireguard");
                return ResponseEntity.ok("NAT rules configured successfully");
            } else {
                return ResponseEntity.internalServerError().body("VpnService not available");
            }
        } catch (Exception e) {
            log.error("Failed to setup NAT: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Failed to setup NAT: " + e.getMessage());
        }
    }

    @DeleteMapping("/{peerIdentifier}")
    public ResponseEntity<Void> deletePeer(@PathVariable String peerIdentifier) {
        log.info("Deleting VPN peer: {}", peerIdentifier);

        try {
            String interfaceName = "wg0"; // Default WireGuard interface

            // Resolve peer name if IP address was provided
            String peerName = peerIdentifier;
            if (peerIdentifier.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                // It's an IP address, find the peer name
                String foundName = findPeerNameByIp(peerIdentifier);
                if (foundName.equals(peerIdentifier)) {
                    // Name not found, fallback failed
                    log.error("Could not find peer name for IP: {}", peerIdentifier);
                    return ResponseEntity.notFound().build();
                }
                peerName = foundName;
                log.info("Resolved IP {} to peer name: {}", peerIdentifier, peerName);
            }

            // Cast to VpnService to access deletePeer method
            if (createPeerUseCase instanceof VpnService vpnService) {
                vpnService.deletePeer(interfaceName, peerName);
                return ResponseEntity.noContent().build();
            } else {
                log.error("CreatePeerUseCase is not an instance of VpnService");
                return ResponseEntity.internalServerError().build();
            }
        } catch (Exception e) {
            log.error("Failed to delete peer: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{peerIdentifier}/config")
    public ResponseEntity<PeerConfigResponse> getPeerConfig(@PathVariable String peerIdentifier) {
        log.info("Fetching config for peer: {}", peerIdentifier);

        try {
            java.nio.file.Path configDir = java.nio.file.Paths.get(wireguardConfigPath);
            log.info("Searching for peer config in: {}", configDir.toAbsolutePath());

            // Search for peer by name or IP address
            java.nio.file.Path peerConfigPath = null;
            String peerName = null;

            // First try as a peer name directly
            java.nio.file.Path directPath = configDir.resolve(peerIdentifier).resolve(peerIdentifier + ".conf");
            if (java.nio.file.Files.exists(directPath)) {
                peerConfigPath = directPath;
                peerName = peerIdentifier;
            } else {
                // Search all peer directories for matching IP
                log.info("Searching by IP address: {}", peerIdentifier);
                try (var stream = java.nio.file.Files.list(configDir)) {
                    var found = stream
                            .filter(java.nio.file.Files::isDirectory)
                            .filter(dir -> {
                                try {
                                    String dirName = dir.getFileName().toString();
                                    java.nio.file.Path confFile = dir.resolve(dirName + ".conf");
                                    log.debug("Checking directory: {} for config file: {}", dirName, confFile);

                                    if (java.nio.file.Files.exists(confFile)) {
                                        String content = java.nio.file.Files.readString(confFile);
                                        for (String line : content.split("\n")) {
                                            if (line.trim().startsWith("Address")) {
                                                String address = line.substring(line.indexOf('=') + 1).trim();
                                                String ip = address.split("/")[0];
                                                log.debug("Found IP {} in {}, comparing with {}", ip, dirName, peerIdentifier);
                                                if (ip.equals(peerIdentifier)) {
                                                    log.info("Match found in directory: {}", dirName);
                                                    return true;
                                                }
                                            }
                                        }
                                    } else {
                                        log.debug("Config file does not exist: {}", confFile);
                                    }
                                } catch (Exception e) {
                                    log.warn("Error reading peer config in {}: {}", dir, e.getMessage());
                                }
                                return false;
                            })
                            .findFirst();

                    if (found.isPresent()) {
                        peerName = found.get().getFileName().toString();
                        peerConfigPath = found.get().resolve(peerName + ".conf");
                        log.info("Found peer: {} at path: {}", peerName, peerConfigPath);
                    } else {
                        log.warn("No matching peer found for IP: {}", peerIdentifier);
                    }
                }
            }

            if (peerConfigPath == null || !java.nio.file.Files.exists(peerConfigPath)) {
                log.warn("Peer config not found for identifier: {}", peerIdentifier);
                return ResponseEntity.notFound().build();
            }

            String configContent = java.nio.file.Files.readString(peerConfigPath);

            // Extract IP address from config
            String ipAddress = "";
            for (String line : configContent.split("\n")) {
                if (line.trim().startsWith("Address")) {
                    ipAddress = line.substring(line.indexOf('=') + 1).trim();
                    break;
                }
            }

            log.info("Found peer config: {} with IP {}", peerName, ipAddress);

            PeerConfigResponse response = new PeerConfigResponse(
                    peerName,
                    ipAddress,
                    configContent
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to read peer config: {}", e.getMessage(), e);
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{peerName}/docker-compose")
    public ResponseEntity<Resource> downloadDockerCompose(
            @PathVariable String peerName,
            @RequestParam(required = false, defaultValue = "vaier.eilertsen.family") String serverUrl,
            @RequestParam(required = false, defaultValue = "51820") String serverPort
    ) {
        log.info("Generating docker-compose for peer: {}", peerName);

        String dockerCompose = generateDockerCompose(peerName, serverUrl, serverPort);
        byte[] content = dockerCompose.getBytes();

        ByteArrayResource resource = new ByteArrayResource(content);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=docker-compose.yml")
                .contentType(MediaType.parseMediaType("application/x-yaml"))
                .contentLength(content.length)
                .body(resource);
    }

    private String generateDockerCompose(String peerName, String serverUrl, String serverPort) {
        return String.format("""
                services:
                  wireguard-client:
                    image: lscr.io/linuxserver/wireguard:latest
                    container_name: wireguard-client
                    cap_add:
                      - NET_ADMIN
                      - SYS_MODULE
                    environment:
                      - PUID=1000
                      - PGID=1000
                      - TZ=Europe/Oslo
                    volumes:
                      - ./wireguard-client/config:/config
                      - /lib/modules:/lib/modules:ro
                    sysctls:
                      - net.ipv4.conf.all.src_valid_mark=1
                    restart: unless-stopped

                # Setup Instructions:
                # 1. Create config directory: mkdir -p ./wireguard-client/config
                # 2. Copy peer config from server at ./wireguard/config/%s/%s.conf
                #    to ./wireguard-client/config/wg0.conf
                # 3. Start client: docker-compose up -d
                # 4. Verify connection: docker exec wireguard-client wg show
                #
                # Server: %s:%s
                """, peerName, peerName, serverUrl, serverPort);
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
}
