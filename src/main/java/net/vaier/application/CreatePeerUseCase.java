package net.vaier.application;

import net.vaier.domain.MachineType;

public interface CreatePeerUseCase {

    CreatedPeerUco createPeer(String name);
    CreatedPeerUco createPeer(String name, MachineType peerType, String lanCidr);
    CreatedPeerUco createPeer(String name, MachineType peerType, String lanCidr, String lanAddress);
    CreatedPeerUco createPeer(String name, MachineType peerType, String lanCidr, String lanAddress,
                              String description);

    /**
     * The outcome of creating a peer.
     *
     * @param id   the peer's immutable identifier — the slug derived from the operator-typed
     *             {@code name} and deduplicated against existing peers. The WireGuard config
     *             directory name; never changes once assigned.
     * @param name the operator-typed display label, stored verbatim.
     */
    record CreatedPeerUco(
        String id,
        String name,
        String ipAddress,
        String publicKey,
        String privateKey,
        String clientConfigFile,
        MachineType peerType
    ) {}
}
