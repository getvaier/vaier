package net.vaier.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Downloadable artifacts available for a VPN peer. The capability matrix — which peer types get
 * which artifacts — is a domain rule mirrored by the relevant {@code Generate*UseCase}s (e.g.
 * {@code GeneratePeerSetupScriptUseCase} returns empty for non-Ubuntu peers). The browser only
 * renders buttons for what {@link #forPeerType(MachineType)} returned.
 *
 * <ul>
 *   <li>{@link #WG_CONFIG}      — the {@code .conf} file every WG-backed peer gets</li>
 *   <li>{@link #QR_CODE}        — a QR-coded config, useful only for the mobile client</li>
 *   <li>{@link #DOCKER_COMPOSE} — Docker Compose template for running the peer as a container —
 *                                only useful on server-class hosts</li>
 *   <li>{@link #SETUP_SCRIPT}   — host-bootstrap setup script, currently Ubuntu-only</li>
 * </ul>
 */
public enum PeerArtifact {
    WG_CONFIG,
    QR_CODE,
    DOCKER_COMPOSE,
    SETUP_SCRIPT;

    public static Set<PeerArtifact> forPeerType(MachineType peerType) {
        if (peerType == null || !peerType.isVpnPeer()) return EnumSet.noneOf(PeerArtifact.class);
        EnumSet<PeerArtifact> out = EnumSet.of(WG_CONFIG);
        if (peerType == MachineType.MOBILE_CLIENT) out.add(QR_CODE);
        if (peerType.isServerType()) out.add(DOCKER_COMPOSE);
        if (peerType == MachineType.UBUNTU_SERVER) out.add(SETUP_SCRIPT);
        return out;
    }
}
