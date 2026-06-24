package net.vaier.application;

import net.vaier.domain.DeviceCategory;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerArtifact;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface GetVpnPeersUseCase {

    /**
     * Fully-assembled view of every VPN peer: WireGuard runtime state from the wg interface,
     * persisted configuration (peer type, lanCidr, lanAddress, description), and geolocation
     * when the peer has a current endpoint. Controllers map this straight to their response
     * DTO — they do no cross-source joining or default-value decisions.
     */
    List<VpnPeerView> getVpnPeers();

    /**
     * @param id                  the peer's immutable identifier (config directory name).
     * @param name                the operator-facing display label; falls back to the id-derived
     *                            label when no config exists yet.
     * @param peerType            the persisted {@link MachineType}; falls back to
     *                            {@link MachineType#defaultType()} when no config exists.
     * @param tunnelIp            the peer's WireGuard tunnel IP — the first {@code allowedIps}
     *                            entry with its mask stripped. Pre-extracted in the domain so the
     *                            browser doesn't split the raw {@code allowedIps} string.
     * @param isServer            true when the peer type is a server kind (Ubuntu / Windows).
     * @param isClient            true when the peer type is a client kind (Mobile / Windows).
     * @param isRelay             true when this server peer relays a LAN — i.e. has a non-blank
     *                            {@code lanCidr}. Browser does not derive this from {@code lanCidr}.
     * @param availableArtifacts  which downloadable artifacts this peer supports — config file,
     *                            QR code, Docker Compose template, setup script. The browser
     *                            only renders buttons for what's in the set.
     * @param geoLocation         present only when the peer has a non-blank endpoint and the
     *                            lookup succeeded; empty otherwise.
     * @param configOutOfDate     true when the peer's on-disk config differs from what current
     *                            generation logic would render (keys preserved) — i.e. a
     *                            {@code Reissue} would change it. The UI surfaces this as a badge.
     * @param deviceCategory      the EFFECTIVE device category (override if pinned, else detected);
     *                            never null. Orthogonal to {@code peerType} — it only picks an icon.
     * @param deviceCategoryOverridden  true when an explicit override is stored (rather than
     *                            auto-detected).
     * @param isInternetGateway   true when this peer is Vaier's designated internet gateway — the
     *                            central internet egress for full-tunnel clients (#174). At most
     *                            one peer is true. The UI renders a single-select toggle from it.
     */
    record VpnPeerView(
        String id,
        String name,
        String publicKey,
        String allowedIps,
        String tunnelIp,
        String endpointIp,
        String endpointPort,
        String latestHandshake,
        boolean connected,
        String transferRx,
        String transferTx,
        MachineType peerType,
        boolean isServer,
        boolean isClient,
        boolean isRelay,
        Set<PeerArtifact> availableArtifacts,
        String lanCidr,
        String lanAddress,
        String description,
        Optional<GeoLocation> geoLocation,
        boolean configOutOfDate,
        DeviceCategory deviceCategory,
        boolean deviceCategoryOverridden,
        boolean isInternetGateway
    ) {}
}
