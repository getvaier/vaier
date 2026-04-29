package net.vaier.application;

import net.vaier.domain.MachineType;

import java.util.Optional;

public interface GetPeerConfigUseCase {

    Optional<PeerConfigResult> getPeerConfig(String peerIdentifier);

    Optional<PeerConfigResult> getPeerConfigByIp(String ipAddress);

    record PeerConfigResult(
        String name,
        String ipAddress,
        String configContent,
        MachineType peerType,
        String lanCidr,
        String lanAddress
    ) {
        public PeerConfigResult(String name, String ipAddress, String configContent, MachineType peerType) {
            this(name, ipAddress, configContent, peerType, null, null);
        }
    }
}
