package net.vaier.domain.port;

import java.util.Optional;

public interface ForGettingPeerConfigurations {

    Optional<PeerConfiguration> getPeerConfigByName(String peerName);

    Optional<PeerConfiguration> getPeerConfigByIp(String ipAddress);

    record PeerConfiguration(
        String name,
        String ipAddress,
        String configContent
    ) {}
}
