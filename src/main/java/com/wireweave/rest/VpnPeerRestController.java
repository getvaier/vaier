package com.wireweave.rest;

import com.wireweave.application.CreatePeerUseCase;
import com.wireweave.domain.VpnClient;
import com.wireweave.domain.port.ForGettingVpnClients;
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
    private final CreatePeerUseCase createPeerUseCase;

    @GetMapping
    public ResponseEntity<List<VpnPeerResponse>> listPeers() {
        log.info("Fetching all VPN peers");
        List<VpnClient> clients = vpnClientService.getClients();
        List<VpnPeerResponse> response = clients.stream()
                .map(client -> new VpnPeerResponse(
                        client.publicKey(),
                        client.allowedIps(),
                        client.endpointIp(),
                        client.endpointPort(),
                        client.latestHandshake(),
                        client.transferRx(),
                        client.transferTx()
                ))
                .toList();
        return ResponseEntity.ok(response);
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

    @GetMapping("/{peerName}/docker-compose")
    public ResponseEntity<Resource> downloadDockerCompose(
            @PathVariable String peerName,
            @RequestParam(required = false, defaultValue = "wireweave.eilertsen.family") String serverUrl,
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
}
