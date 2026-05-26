package net.vaier.application;

import net.vaier.domain.GeoLocation;
import net.vaier.domain.MachineType;

import java.util.List;
import java.util.Optional;

public interface GetVpnPeersUseCase {

    /**
     * Fully-assembled view of every VPN peer: WireGuard runtime state from the wg interface,
     * persisted configuration (peer type, lanCidr, lanAddress, description), and geolocation
     * when the peer has a current endpoint. Controllers map this straight to their response
     * DTO — they do no cross-source joining or default-value decisions.
     */
    List<VpnPeerView> getVpnPeers();

    /**
     * @param id          the peer's immutable identifier (config directory name).
     * @param name        the operator-facing display label; falls back to the id-derived label
     *                    when no config exists yet.
     * @param peerType    the persisted {@link MachineType}; falls back to
     *                    {@link MachineType#defaultType()} when no config exists.
     * @param geoLocation present only when the peer has a non-blank endpoint and the lookup
     *                    succeeded; empty otherwise.
     */
    record VpnPeerView(
        String id,
        String name,
        String publicKey,
        String allowedIps,
        String endpointIp,
        String endpointPort,
        String latestHandshake,
        boolean connected,
        String transferRx,
        String transferTx,
        MachineType peerType,
        String lanCidr,
        String lanAddress,
        String description,
        Optional<GeoLocation> geoLocation
    ) {}
}
