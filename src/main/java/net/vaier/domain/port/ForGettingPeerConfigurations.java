package net.vaier.domain.port;

import net.vaier.domain.MachineType;

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
        MachineType peerType,
        String lanCidr,
        String lanAddress,
        String description
    ) {
        public PeerConfiguration(String name, String ipAddress, String configContent) {
            this(name, ipAddress, configContent, MachineType.UBUNTU_SERVER, null, null, null);
        }

        public PeerConfiguration(String name, String ipAddress, String configContent,
                                 MachineType peerType, String lanCidr) {
            this(name, ipAddress, configContent, peerType, lanCidr, null, null);
        }

        public PeerConfiguration(String name, String ipAddress, String configContent,
                                 MachineType peerType, String lanCidr, String lanAddress) {
            this(name, ipAddress, configContent, peerType, lanCidr, lanAddress, null);
        }
    }
}
