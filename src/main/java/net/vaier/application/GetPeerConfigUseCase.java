package net.vaier.application;

import net.vaier.domain.MachineType;
import net.vaier.domain.PeerId;

import java.util.Optional;

public interface GetPeerConfigUseCase {

    Optional<PeerConfigResult> getPeerConfig(String peerIdentifier);

    Optional<PeerConfigResult> getPeerConfigByIp(String ipAddress);

    /** As {@link #getPeerConfig}, for handing the config out: refused for a personal device. */
    Optional<PeerConfigResult> retrievePeerConfig(String peerIdentifier);

    /**
     * @param id   the peer's immutable identifier (WireGuard config directory name).
     * @param name the operator-facing display label.
     * @param deviceHeldKey whether this peer's private key was minted on the device — carried so the web
     *             layer can ask {@code PeerArtifact.forPeer} the whole question rather than assume it.
     */
    record PeerConfigResult(
        String id,
        String name,
        String ipAddress,
        String configContent,
        MachineType peerType,
        String lanCidr,
        String lanAddress,
        String description,
        boolean deviceHeldKey
    ) {
        public PeerConfigResult(String id, String ipAddress, String configContent, MachineType peerType) {
            this(id, PeerId.display(id), ipAddress, configContent, peerType, null, null, null, false);
        }

        public PeerConfigResult(String id, String ipAddress, String configContent,
                                MachineType peerType, String lanCidr, String lanAddress) {
            this(id, PeerId.display(id), ipAddress, configContent, peerType, lanCidr, lanAddress, null, false);
        }
    }
}
