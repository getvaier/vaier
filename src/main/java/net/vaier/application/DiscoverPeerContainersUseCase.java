package net.vaier.application;

import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;

import java.util.List;

public interface DiscoverPeerContainersUseCase {

    List<PeerContainers> discoverAll();
}
