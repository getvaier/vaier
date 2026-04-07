package net.vaier.domain.port;

import net.vaier.domain.PeerType;

import java.util.List;
import java.util.Optional;

public interface ForGettingPeerConfigurations {

    Optional<PeerConfiguration> getPeerConfigByName(String peerName);

    Optional<PeerConfiguration> getPeerConfigByIp(String ipAddress);

    List<PeerConfiguration> getAllPeerConfigs();

    record PeerConfiguration(
        String name,
        String ipAddress,
        String configContent,
        PeerType peerType,
        String lanCidr
    ) {
        public PeerConfiguration(String name, String ipAddress, String configContent) {
            this(name, ipAddress, configContent, PeerType.UBUNTU_SERVER, null);
        }
    }
}
