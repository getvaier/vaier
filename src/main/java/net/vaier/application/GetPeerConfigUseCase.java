package net.vaier.application;

import java.util.Optional;

public interface GetPeerConfigUseCase {

    Optional<PeerConfigResult> getPeerConfig(String peerIdentifier);

    record PeerConfigResult(
        String name,
        String ipAddress,
        String configContent
    ) {}
}
