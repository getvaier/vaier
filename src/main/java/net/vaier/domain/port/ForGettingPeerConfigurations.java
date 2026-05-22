package net.vaier.domain.port;

import net.vaier.domain.MachineType;
import net.vaier.domain.PeerId;

import java.util.List;
import java.util.Optional;

public interface ForGettingPeerConfigurations {

    Optional<PeerConfiguration> getPeerConfigByName(String peerId);

    Optional<PeerConfiguration> getPeerConfigByIp(String ipAddress);

    List<PeerConfiguration> getAllPeerConfigs();

    /**
     * A peer's persisted configuration.
     *
     * @param id   the peer's immutable identifier — its WireGuard config directory name, REST
     *             path segment, and routing key. Derived from the operator-typed name when the
     *             peer is created and never changed afterwards.
     * @param name the operator-facing display label, freely editable. Always populated: for
     *             peers created before the id/name split it falls back to {@link PeerId#display}
     *             of the id.
     */
    record PeerConfiguration(
        String id,
        String name,
        String ipAddress,
        String configContent,
        MachineType peerType,
        String lanCidr,
        String lanAddress,
        String description
    ) {
        public PeerConfiguration(String id, String ipAddress, String configContent) {
            this(id, PeerId.display(id), ipAddress, configContent, MachineType.UBUNTU_SERVER,
                null, null, null);
        }

        public PeerConfiguration(String id, String ipAddress, String configContent,
                                 MachineType peerType, String lanCidr) {
            this(id, PeerId.display(id), ipAddress, configContent, peerType, lanCidr, null, null);
        }

        public PeerConfiguration(String id, String ipAddress, String configContent,
                                 MachineType peerType, String lanCidr, String lanAddress) {
            this(id, PeerId.display(id), ipAddress, configContent, peerType, lanCidr, lanAddress, null);
        }
    }
}
