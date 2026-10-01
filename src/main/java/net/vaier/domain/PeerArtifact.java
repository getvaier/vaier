package net.vaier.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Downloadable artifacts available for a VPN peer. The capability matrix — which peer types get
 * which artifacts — is a domain rule mirrored by the relevant {@code Generate*UseCase}s (e.g.
 * {@code GeneratePeerSetupScriptUseCase} returns empty for non-Ubuntu peers). The browser only
 * renders buttons for what {@link #forPeer(MachineType, boolean)} returned.
 *
 * <ul>
 *   <li>{@link #WG_CONFIG}      — the {@code .conf} file every WG-backed peer gets</li>
 *   <li>{@link #DOCKER_COMPOSE} — Docker Compose template for running the peer as a container —
 *                                only useful on server-class hosts</li>
 *   <li>{@link #SETUP_SCRIPT}   — host-bootstrap setup script, currently Ubuntu-only</li>
 * </ul>
 */
public enum PeerArtifact {
    WG_CONFIG,
    DOCKER_COMPOSE,
    SETUP_SCRIPT;

    /**
     * What a peer offers for download, given whether its private key is a {@code Device-held key}.
     *
     * <p>A device-held key was minted on the device and has never existed in Vaier, so there is no
     * installable config to hand back, no QR to photograph, and nothing to leak: the set is empty
     * whatever the peer type. That is the whole security argument for an {@code Enrolment}, and it
     * only holds if it is decided in one place rather than remembered at each of the endpoints.
     */
    public static Set<PeerArtifact> forPeer(MachineType peerType, boolean deviceHeldKey) {
        return deviceHeldKey ? EnumSet.noneOf(PeerArtifact.class) : forPeerType(peerType);
    }

    /** As {@link #forPeer} for a peer that is known not to hold its own key — or where there is no peer. */
    public static Set<PeerArtifact> forPeerType(MachineType peerType) {
        // A personal device joins through the Vaier app with its own key: nothing to hand out.
        if (peerType == null || !peerType.isVpnPeer() || peerType.joinsThroughVaierApp()) {
            return EnumSet.noneOf(PeerArtifact.class);
        }
        EnumSet<PeerArtifact> out = EnumSet.of(WG_CONFIG);
        if (peerType.isServerType()) out.add(DOCKER_COMPOSE);
        if (peerType == MachineType.UBUNTU_SERVER) out.add(SETUP_SCRIPT);
        return out;
    }
}
