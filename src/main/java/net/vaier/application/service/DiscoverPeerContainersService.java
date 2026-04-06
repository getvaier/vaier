package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.domain.DockerService;
import net.vaier.domain.PeerType;
import net.vaier.domain.Server;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DiscoverPeerContainersService implements DiscoverPeerContainersUseCase {

    private final ForGettingVpnClients forGettingVpnClients;
    private final ForResolvingPeerNames forResolvingPeerNames;
    private final ForGettingServerInfo forGettingServerInfo;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;

    @Override
    public List<PeerContainers> discoverAll() {
        List<VpnClient> clients = forGettingVpnClients.getClients();
        List<PeerContainers> results = new ArrayList<>();

        for (VpnClient client : clients) {
            String vpnIp = client.allowedIps().split("/")[0];
            String peerName = forResolvingPeerNames.resolvePeerNameByIp(vpnIp);

            PeerType peerType = forGettingPeerConfigurations.getPeerConfigByIp(vpnIp)
                    .map(ForGettingPeerConfigurations.PeerConfiguration::peerType)
                    .orElse(PeerType.UBUNTU_SERVER);

            if (!peerType.isServerType()) {
                log.debug("Skipping Docker discovery for non-server peer {} ({}) of type {}", peerName, vpnIp, peerType);
                continue;
            }

            if (!isPeerConnected(client)) {
                log.debug("Skipping Docker discovery for disconnected peer {} ({})", peerName, vpnIp);
                results.add(new PeerContainers(peerName, vpnIp, "UNREACHABLE", List.of()));
                continue;
            }

            try {
                Server server = new Server(vpnIp, 2375, false);
                List<DockerService> containers = forGettingServerInfo.getServicesWithExposedPorts(server);
                log.info("Discovered {} containers on peer {} ({})", containers.size(), peerName, vpnIp);
                results.add(new PeerContainers(peerName, vpnIp, "OK", containers));
            } catch (Exception e) {
                log.warn("Failed to query Docker on peer {} ({}): {}", peerName, vpnIp, e.getMessage());
                results.add(new PeerContainers(peerName, vpnIp, "UNREACHABLE", List.of()));
            }
        }

        return results;
    }

    private boolean isPeerConnected(VpnClient peer) {
        try {
            long handshake = Long.parseLong(peer.latestHandshake());
            long now = System.currentTimeMillis() / 1000;
            return handshake > 0 && (now - handshake) < 300;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
