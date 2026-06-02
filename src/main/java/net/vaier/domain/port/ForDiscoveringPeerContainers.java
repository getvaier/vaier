package net.vaier.domain.port;

import net.vaier.domain.DockerService;

import java.util.List;

/**
 * Driven query port exposing the cached server-peer container scrape. Mirror of the inbound
 * {@code DiscoverPeerContainersUseCase}; used by other domains' services (e.g. publishing) that
 * need a read-only view of discovered peer containers without coupling to the inbound use case.
 */
public interface ForDiscoveringPeerContainers {

    List<PeerContainers> discoverAll();

    record PeerContainers(
            String peerName,
            String vpnIp,
            String status,
            List<DockerService> containers,
            boolean wireguardOutdated,
            String wireguardExpectedImage
    ) {}
}
