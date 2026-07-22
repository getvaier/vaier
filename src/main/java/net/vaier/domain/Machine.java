package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;

import java.util.Collection;
import java.util.Map;

/**
 * Unified read projection for every machine Vaier manages — both WireGuard peers
 * (the four {@link MachineType#isVpnPeer() VPN-backed} types) and {@link MachineType#LAN_SERVER}
 * entries that sit on a relay's LAN. WG-only fields ({@code publicKey}, {@code allowedIps},
 * runtime state) are null for {@code LAN_SERVER}; {@code dockerPort} is non-null only for
 * LAN servers with {@code runsDocker=true}.
 */
public record Machine(
    String name,
    MachineType type,
    String publicKey,
    String allowedIps,
    String endpointIp,
    String endpointPort,
    String latestHandshake,
    String transferRx,
    String transferTx,
    String lanCidr,
    String lanAddress,
    boolean runsDocker,
    Integer dockerPort,
    DeviceCategory deviceCategory,
    Boolean sshAccessOverride
) {

    /**
     * Whether Vaier offers SSH (the credential control now, the web terminal later) for a machine by
     * default, before any operator override — the smart default seeded from what the machine is. True
     * when the device is not an {@link DeviceCategory#isAppliance() appliance} and it is either
     * {@link DeviceCategory#sshCapableByDefault() SSH-capable by category} or a
     * {@link MachineType#isServerType() server-type} machine. An appliance category vetoes the
     * server-type fallback, so a LAN server that is really a printer stays SSH-off.
     */
    public static boolean defaultSshAccess(DeviceCategory deviceCategory, MachineType type) {
        return !deviceCategory.isAppliance()
            && (deviceCategory.sshCapableByDefault() || type.isServerType());
    }

    /** This machine's SSH-access default from its own category and type. */
    public boolean defaultSshAccess() {
        return defaultSshAccess(deviceCategory, type);
    }

    /**
     * Whether Vaier offers SSH for this machine: the operator's {@link #sshAccessOverride() override}
     * when one is set, otherwise the {@link #defaultSshAccess() smart default}. The override — not the
     * device category — is authoritative; the category only seeds the default.
     */
    public boolean effectiveSshAccess() {
        return sshAccessOverride != null ? sshAccessOverride : defaultSshAccess();
    }

    /**
     * Projects a VPN peer into a {@code Machine}. {@code client} is the peer's live WireGuard
     * runtime, or null when the peer has no current session — every runtime field is then null.
     */
    public static Machine fromPeer(PeerConfiguration peer, VpnClient client) {
        return new Machine(
            peer.name(),
            peer.peerType(),
            client == null ? null : client.publicKey(),
            client == null ? null : client.allowedIps(),
            client == null ? null : client.endpointIp(),
            client == null ? null : client.endpointPort(),
            client == null ? null : client.latestHandshake(),
            client == null ? null : client.transferRx(),
            client == null ? null : client.transferTx(),
            peer.lanCidr(),
            peer.lanAddress(),
            peer.peerType().isServerType(),
            null,
            peer.effectiveDeviceCategory(),
            peer.sshAccess()
        );
    }

    /**
     * Projects a LAN server into a {@code Machine}. {@code anchorLanCidr} is the CIDR of the
     * relay peer (or Vaier server) that routes to it, or null when no anchor covers it.
     */
    public static Machine fromLanServer(LanServer server, String anchorLanCidr) {
        return new Machine(
            server.name(),
            MachineType.LAN_SERVER,
            null, null, null, null, null, null, null,
            anchorLanCidr,
            server.lanAddress(),
            server.runsDocker(),
            server.dockerPort(),
            server.effectiveDeviceCategory(),
            server.sshAccessOverride()
        );
    }

    /**
     * The Vaier server host itself as a singleton synthetic machine (#311) — the box running the
     * whole stack, which is neither a WireGuard peer nor a LAN server. It carries the canonical
     * {@link LanAnchor#VAIER_SERVER_NAME reserved name}, device category {@link DeviceCategory#SERVER},
     * and defaults SSH-access on (it is a server). {@code sshAccessOverride} is the operator's pinned
     * value from the Vaier config, or null to use the default. Its type reuses {@link MachineType#UBUNTU_SERVER}
     * rather than a dedicated enum value so it never disturbs peer/LAN routing logic — Vaier never
     * generates WireGuard config from a {@code Machine} projection. It {@code runsDocker} — the box is
     * itself the Docker engine hosting the whole compose stack — so the Explorer grows a {@code containers}
     * entry for it; the port is null because Vaier reaches that engine over the local socket, not a TCP port.
     * Every peer/LAN-only field is null.
     */
    public static Machine vaierServer(Boolean sshAccessOverride) {
        return new Machine(
            LanAnchor.VAIER_SERVER_NAME, MachineType.UBUNTU_SERVER,
            null, null, null, null, null, null, null,
            null, null, true, null, DeviceCategory.SERVER, sshAccessOverride);
    }

    /**
     * Whether this machine is reachable right now, from already-cached signals only — no fresh probe.
     * The {@link LanAnchor#VAIER_SERVER_NAME Vaier server} is always reachable (Vaier runs on it); a
     * {@link MachineType#LAN_SERVER} is reachable when the cached LAN reachability map reports its
     * address {@link Reachability#OK}; every other machine is a VPN peer, reachable when its tunnel
     * handshake is still fresh. Peer freshness reuses {@link VpnClient#isConnected()} rather than
     * re-deriving the staleness rule here — this machine already carries the peer's runtime fields.
     */
    public boolean isReachable(Map<String, Reachability> lanReachability) {
        if (LanAnchor.VAIER_SERVER_NAME.equals(name)) {
            return true;
        }
        if (type == MachineType.LAN_SERVER) {
            return lanReachability != null && lanReachability.get(lanAddress) == Reachability.OK;
        }
        return new VpnClient(publicKey, allowedIps, endpointIp, endpointPort, latestHandshake,
            transferRx, transferTx).isConnected();
    }

    /**
     * True when {@code candidate} collides with an existing machine name. Machine names are
     * unique across all of Vaier (#284) — every machine, whether a VPN peer or a LAN server,
     * has a distinct name, so an operator is never shown two machines wearing the same label
     * and Vaier never confuses one for the other. Comparison is case-insensitive and ignores
     * surrounding whitespace ("nas", "NAS" and " nas " are the same name). A null or blank
     * candidate never collides, and null/blank entries in {@code existingNames} are skipped.
     */
    public static boolean nameIsTaken(String candidate, Collection<String> existingNames) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalized = candidate.trim();
        return existingNames.stream()
            .filter(n -> n != null && !n.isBlank())
            .anyMatch(n -> n.trim().equalsIgnoreCase(normalized));
    }
}
