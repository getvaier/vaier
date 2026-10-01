package net.vaier.application;

import lombok.Builder;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerArtifact;
import net.vaier.domain.Placement;
import net.vaier.domain.PositionTrail;

import java.time.Instant;
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
     * @param machineId           the peer machine's {@link net.vaier.domain.MachineId} as its canonical
     *                            string, or null for a live WireGuard peer with no stored configuration —
     *                            such a peer is in no machine registry, and inventing an id here would be
     *                            minting one, which identity never is. Beside {@code id}, which addresses
     *                            the peer's own endpoints: this addresses the MACHINE, and is what a caller
     *                            holding a row from {@code /machines} joins on. A name cannot do that job —
     *                            it is editable, and one character of disagreement between the two lists
     *                            silently turns a peer into a LAN server in the caller's eyes.
     * @param name                the operator-facing display label; falls back to the id-derived
     *                            label when no config exists yet.
     * @param peerType            the persisted {@link MachineType}; falls back to
     *                            {@link MachineType#defaultType()} when no config exists.
     * @param tunnelIp            the peer's WireGuard tunnel IP — the first {@code allowedIps}
     *                            entry with its mask stripped. Pre-extracted in the domain so the
     *                            browser doesn't split the raw {@code allowedIps} string.
     * @param isServer            true when the peer type is a server kind.
     * @param isClient            true when the peer type is a client kind (Mobile / Windows).
     * @param isRelay             true when this server peer relays a LAN — i.e. has a non-blank
     *                            {@code lanCidr}. Browser does not derive this from {@code lanCidr}.
     * @param availableArtifacts  which downloadable artifacts this peer supports — config file,
     *                            Docker Compose template, setup script. The browser
     *                            only renders buttons for what's in the set.
     * @param geoLocation         the raw ISP estimate: present only when the peer has a non-blank
     *                            endpoint and the lookup succeeded; empty otherwise. Kept as it is —
     *                            it is an input to {@code placement}, not a substitute for it.
     * @param placement           where the machine actually is, decided by {@link Placement} from the
     *                            reported position and the ISP estimate. Empty when Vaier has no
     *                            honest answer — notably for a disconnected peer that has never reported,
     *                            whose endpoint is only the last one WireGuard happened to see.
     * @param positionTrail       where this machine has been: the retained {@link PositionTrail} of its own
     *                            reported positions, oldest first. Empty for a machine that has never
     *                            reported — an ISP estimate never joins a trail, because a carrier's whole
     *                            block resolves to one point and a journey drawn from those is a line from
     *                            the carrier's head office to itself.
     * @param configOutOfDate     true when the peer's on-disk config differs from what current
     *                            generation logic would render (keys preserved) — i.e. a
     *                            {@code Reissue} would change it. The UI surfaces this as a badge.
     * @param deviceCategory      the EFFECTIVE device category (override if pinned, else detected);
     *                            never null. Orthogonal to {@code peerType} — it only picks an icon.
     * @param deviceCategoryOverridden  true when an explicit override is stored (rather than
     *                            auto-detected).
     * @param lastServiceReached  what this machine most recently opened through the forward-auth chain,
     *                            and when. Empty unless the machine has reached something <em>over the
     *                            tunnel</em> — an access from anywhere else identifies a person, not a
     *                            device, and Vaier does not guess which one.
     */
    @Builder
    record VpnPeerView(
        String id,
        String machineId,
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
        /**
         * True when this peer's private key was minted on the device and has never existed in Vaier —
         * an {@code Enrolment}. Carried in its own right, not only as an empty {@code availableArtifacts}:
         * it is what tells the pane that a {@code Reissue} and a regeneration are both meaningless here.
         */
        boolean deviceHeldKey,
        /** True for a personal device: no Reissue or regeneration, it rejoins from its Vaier app. */
        boolean joinsThroughVaierApp,
        String lanCidr,
        String lanAddress,
        String description,
        Optional<GeoLocation> geoLocation,
        boolean configOutOfDate,
        DeviceCategory deviceCategory,
        boolean deviceCategoryOverridden,
        boolean sshAccess,
        Optional<Placement> placement,
        PositionTrail positionTrail,
        Optional<LastServiceReachedView> lastServiceReached
    ) {
        /**
         * Twenty-seven components, several of them same-typed and adjacent, so the {@link Builder} is what
         * every call site uses — and an absent optional is the empty one, never null: a builder leaves out
         * what a caller does not care about, and {@code Optional} that can be null is the worst of both.
         * A trail nobody set is the empty trail for the same reason.
         */
        public VpnPeerView {
            geoLocation = geoLocation == null ? Optional.empty() : geoLocation;
            placement = placement == null ? Optional.empty() : placement;
            positionTrail = positionTrail == null ? PositionTrail.empty() : positionTrail;
            lastServiceReached = lastServiceReached == null ? Optional.empty() : lastServiceReached;
        }
    }

    /**
     * The last service a machine reached, as the browser needs it.
     *
     * @param host        the gated host that was reached — always known.
     * @param displayName the same label the launchpad tile carries, or null when Vaier publishes no route
     *                    for that host (its own console is one such host). The host then stands alone.
     * @param at          when it was reached.
     */
    record LastServiceReachedView(String host, String displayName, Instant at) {}
}
