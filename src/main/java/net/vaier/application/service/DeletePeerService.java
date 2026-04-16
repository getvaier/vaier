package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeletePeerUseCase;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForDeletingVpnPeers;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeletePeerService implements DeletePeerUseCase {

    private final ForResolvingPeerNames peerNameResolver;
    private final ForDeletingVpnPeers vpnPeerDeleter;
    private final ForGettingPeerConfigurations peerConfigProvider;
    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final DeletePublishedServiceUseCase deletePublishedServiceUseCase;

    @Override
    public void deletePeer(String peerIdentifier) {
        log.info("Deleting VPN peer: {}", peerIdentifier);

        // Resolve peer name if IP address was provided
        String peerName = peerIdentifier;
        if (peerIdentifier.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            String resolvedName = peerNameResolver.resolvePeerNameByIp(peerIdentifier);
            if (resolvedName.equals(peerIdentifier)) {
                log.error("Could not find peer name for IP: {}", peerIdentifier);
                throw new IllegalArgumentException("Peer not found for IP: " + peerIdentifier);
            }
            peerName = resolvedName;
            log.info("Resolved IP {} to peer name: {}", peerIdentifier, peerName);
        }

        deletePublishedServicesForPeer(peerName);

        vpnPeerDeleter.deletePeer(peerName);
        log.info("Successfully deleted peer: {}", peerName);
    }

    private void deletePublishedServicesForPeer(String peerName) {
        peerConfigProvider.getPeerConfigByName(peerName).ifPresent(config -> {
            String peerIp = config.ipAddress();
            log.info("Looking for published services pointing to peer {} (IP: {})", peerName, peerIp);

            List<ReverseProxyRoute> routes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
            routes.stream()
                .filter(route -> peerIp.equals(route.getAddress()))
                .forEach(route -> {
                    log.info("Deleting published service {} pointing to peer {}", route.getDomainName(), peerName);
                    deletePublishedServiceUseCase.deleteService(route.getDomainName());
                });
        });
    }
}
