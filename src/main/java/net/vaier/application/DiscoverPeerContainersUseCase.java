package net.vaier.application;

import net.vaier.domain.DockerService;

import java.util.List;

public interface DiscoverPeerContainersUseCase {

    List<PeerContainers> discoverAll();

    record PeerContainers(
            String peerName,
            String vpnIp,
            String status,
            List<DockerService> containers
    ) {}
}
