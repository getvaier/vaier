package net.vaier.application;

import net.vaier.domain.PeerType;

import java.util.Optional;

public interface GetPeerConfigUseCase {

    Optional<PeerConfigResult> getPeerConfig(String peerIdentifier);

    record PeerConfigResult(
        String name,
        String ipAddress,
        String configContent,
        PeerType peerType
    ) {}
}
