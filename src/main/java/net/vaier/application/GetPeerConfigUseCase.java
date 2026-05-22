package net.vaier.application;

import net.vaier.domain.MachineType;
import net.vaier.domain.PeerId;

import java.util.Optional;

public interface GetPeerConfigUseCase {

    Optional<PeerConfigResult> getPeerConfig(String peerIdentifier);

    Optional<PeerConfigResult> getPeerConfigByIp(String ipAddress);

    /**
     * @param id   the peer's immutable identifier (WireGuard config directory name).
     * @param name the operator-facing display label.
     */
    record PeerConfigResult(
        String id,
        String name,
        String ipAddress,
        String configContent,
        MachineType peerType,
        String lanCidr,
        String lanAddress,
        String description
    ) {
        public PeerConfigResult(String id, String ipAddress, String configContent, MachineType peerType) {
            this(id, PeerId.display(id), ipAddress, configContent, peerType, null, null, null);
        }

        public PeerConfigResult(String id, String ipAddress, String configContent,
                                MachineType peerType, String lanCidr, String lanAddress) {
            this(id, PeerId.display(id), ipAddress, configContent, peerType, lanCidr, lanAddress, null);
        }
    }
}
