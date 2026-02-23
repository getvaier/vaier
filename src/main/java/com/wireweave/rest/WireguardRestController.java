package com.wireweave.rest;

import com.wireweave.application.CreatePeerUseCase;
import com.wireweave.application.CreatePeerUseCase.CreatedPeerUco;
import com.wireweave.application.GetWireguardConfigUseCase;
import com.wireweave.application.GetWireguardConfigUseCase.WireguardConfigUco;
import com.wireweave.application.GetWireguardConfigUseCase.WireguardPeerUco;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/wireguard")
public class WireguardRestController {

    private final GetWireguardConfigUseCase getWireguardConfigUseCase;
    private final CreatePeerUseCase createPeerUseCase;

    @Value("${wireguard.config.path:c:/tmp/wireguard}")
    private String configBasePath;

    public WireguardRestController(GetWireguardConfigUseCase getWireguardConfigUseCase,
                                   CreatePeerUseCase createPeerUseCase) {
        this.getWireguardConfigUseCase = getWireguardConfigUseCase;
        this.createPeerUseCase = createPeerUseCase;
    }

    @GetMapping("/{interfaceName}/config")
    public WireguardConfigResponse getConfig(@PathVariable String interfaceName) {
        WireguardConfigUco config = getWireguardConfigUseCase.getConfig(interfaceName);
        return new WireguardConfigResponse(
                new WireguardInterfaceResponse(
                        config.interfaceConfig().address(),
                        config.interfaceConfig().listenPort(),
                        config.interfaceConfig().privateKeyPath(),
                        config.interfaceConfig().publicKey(),
                        config.interfaceConfig().postUpCommands(),
                        config.interfaceConfig().postDownCommands()
                ),
                config.peers().stream().map(this::toResponse).toList()
        );
    }

    @GetMapping("/{interfaceName}/peers")
    public List<WireguardPeerResponse> getPeers(@PathVariable String interfaceName) {
        return getWireguardConfigUseCase.getPeers(interfaceName).stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping("/{interfaceName}/peers")
    public CreatePeerResponse createPeer(@PathVariable String interfaceName,
                                         @RequestBody CreatePeerRequest request) {
        CreatedPeerUco peer = createPeerUseCase.createPeer(interfaceName, request.name());
        return new CreatePeerResponse(
                peer.name(),
                peer.ipAddress(),
                peer.publicKey(),
                peer.privateKey(),
                peer.clientConfigFile()
        );
    }

    @GetMapping("/{interfaceName}/peers/{peerName}/config")
    public ResponseEntity<Resource> downloadClientConfig(@PathVariable String interfaceName,
                                                          @PathVariable String peerName) {
        // Sanitize peer name to match saved filename
        String sanitizedName = peerName.replaceAll("[^a-zA-Z0-9-_]", "_");
        String fileName = sanitizedName + ".conf";
        
        Path clientConfigPath = Paths.get(configBasePath, "clients", fileName);
        File file = clientConfigPath.toFile();
        
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        
        Resource resource = new FileSystemResource(file);
        
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("application/x-wireguard-config"))
                .body(resource);
    }

    private WireguardPeerResponse toResponse(WireguardPeerUco peer) {
        return new WireguardPeerResponse(
                peer.name(),
                peer.publicKey(),
                peer.allowedIPs(),
                peer.endpoint(),
                peer.persistentKeepalive()
        );
    }

    public record WireguardConfigResponse(
            WireguardInterfaceResponse interfaceConfig,
            List<WireguardPeerResponse> peers
    ) {}

    public record WireguardInterfaceResponse(
            String address,
            Integer listenPort,
            String privateKeyPath,
            String publicKey,
            List<String> postUpCommands,
            List<String> postDownCommands
    ) {}

    public record WireguardPeerResponse(
            String name,
            String publicKey,
            String allowedIPs,
            String endpoint,
            Integer persistentKeepalive
    ) {}

    public record CreatePeerRequest(
            String name
    ) {}

    public record CreatePeerResponse(
            String name,
            String ipAddress,
            String publicKey,
            String privateKey,
            String clientConfigFile
    ) {}
}
