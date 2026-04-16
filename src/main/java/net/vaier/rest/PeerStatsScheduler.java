package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ForPublishingEvents;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.ResolveVpnPeerNameUseCase;
import net.vaier.domain.VpnClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PeerStatsScheduler {

    private final GetVpnClientsUseCase vpnClients;
    private final ResolveVpnPeerNameUseCase peerNameResolver;
    private final ForPublishingEvents eventPublisher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 10000)
    public void publishPeerStats() {
        try {
            List<VpnClient> clients = vpnClients.getClients();
            List<Map<String, String>> stats = clients.stream()
                    .map(client -> {
                        String peerIp = client.allowedIps().split("/")[0];
                        String peerName = peerNameResolver.resolvePeerNameByIp(peerIp);
                        return Map.of(
                                "name", peerName != null ? peerName : peerIp,
                                "latestHandshake", client.latestHandshake(),
                                "transferRx", client.transferRx(),
                                "transferTx", client.transferTx(),
                                "endpointIp", client.endpointIp() != null ? client.endpointIp() : "",
                                "endpointPort", client.endpointPort() != null ? client.endpointPort() : ""
                        );
                    })
                    .toList();
            eventPublisher.publish("vpn-peers", "peers-stats", objectMapper.writeValueAsString(stats));
        } catch (Exception e) {
            log.debug("Failed to publish peer stats via SSE: {}", e.getMessage());
        }
    }
}
