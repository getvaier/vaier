package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeletePeerUseCase;
import net.vaier.domain.port.ForDeletingVpnPeers;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeletePeerService implements DeletePeerUseCase {

    private final ForResolvingPeerNames peerNameResolver;
    private final ForDeletingVpnPeers vpnPeerDeleter;

    @Override
    public void deletePeer(String interfaceName, String peerIdentifier) {
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

        vpnPeerDeleter.deletePeer(interfaceName, peerName);
        log.info("Successfully deleted peer: {}", peerName);
    }
}
