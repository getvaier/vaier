package com.wireweave.rest;

import com.wireweave.application.GetWireguardConfigUseCase;
import com.wireweave.application.GetWireguardConfigUseCase.WireguardConfigUco;
import com.wireweave.application.GetWireguardConfigUseCase.WireguardPeerUco;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/wireguard")
public class WireguardRestController {

    private final GetWireguardConfigUseCase getWireguardConfigUseCase;

    public WireguardRestController(GetWireguardConfigUseCase getWireguardConfigUseCase) {
        this.getWireguardConfigUseCase = getWireguardConfigUseCase;
    }

    @GetMapping("/{interfaceName}/config")
    public WireguardConfigResponse getConfig(@PathVariable String interfaceName) {
        WireguardConfigUco config = getWireguardConfigUseCase.getConfig(interfaceName);
        return new WireguardConfigResponse(
                new WireguardInterfaceResponse(
                        config.interfaceConfig().address(),
                        config.interfaceConfig().listenPort(),
                        config.interfaceConfig().privateKeyPath(),
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
}
