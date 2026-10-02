package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Unified read projection for every machine Vaier manages — both WireGuard peers
 * (the four {@link MachineType#isVpnPeer() VPN-backed} types) and {@link MachineType#LAN_SERVER}
 * entries that sit on a relay's LAN. WG-only fields ({@code publicKey}, {@code allowedIps},
 * runtime state) are null for {@code LAN_SERVER}; {@code dockerPort} is non-null only for
 * LAN servers with {@code runsDocker=true}.
 */
public record Machine(
    MachineId id,
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
    Boolean sshAccessOverride,
    long goodbyeEpoch
) {

    /** A machine whose app has not said goodbye — every machine but a peer read from a live tunnel. */
    public Machine(MachineId id, String name, MachineType type, String publicKey, String allowedIps,
                   String endpointIp, String endpointPort, String latestHandshake, String transferRx,
                   String transferTx, String lanCidr, String lanAddress, boolean runsDocker, Integer dockerPort,
                   DeviceCategory deviceCategory, Boolean sshAccessOverride) {
        this(id, name, type, publicKey, allowedIps, endpointIp, endpointPort, latestHandshake, transferRx,
            transferTx, lanCidr, lanAddress, runsDocker, dockerPort, deviceCategory, sshAccessOverride, 0L);
    }

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
     * Whether this machine could ever be a <b>relay peer</b> — carry a whole network into the fleet. Only
     * a VPN peer of a {@link MachineType#isServerType() server type} can: a {@link MachineType#LAN_SERVER}
     * is reached <em>through</em> a relay and has no tunnel of its own to route into, and a personal
     * device is not somebody's gateway.
     *
     * <p>It is a machine's own reading of itself, not a rule for a form to re-derive — the Explorer's edit
     * form has long asked the same question as {@code peers.has(id) && SERVER_TYPES.has(type)}, and #333
     * needed to ask it a second time before offering a detected network.
     */
    public boolean canRelayALan() {
        return type != null && type.isVpnPeer() && type.isServerType();
    }

    /**
     * Whether the <b>LAN setup script</b> is offered for this machine. Only a LAN server is set up by
     * running a script on it — a peer's comes with its config — and only one with a shell to run it in.
     * An {@link DeviceCategory#isAppliance() appliance} has none unless something says otherwise: it runs
     * Docker, or the operator let Vaier SSH into it. A sprinkler controller does neither, and being
     * relay-anchored does not give it a shell.
     */
    public boolean acceptsSetupScript() {
        return type == MachineType.LAN_SERVER
            && acceptsSetupScript(runsDocker, effectiveSshAccess(), deviceCategory);
    }

    /** The one spelling of the rule, shared with {@link LanServer#acceptsSetupScript} as {@link #defaultSshAccess} is. */
    public static boolean acceptsSetupScript(boolean runsDocker, boolean effectiveSshAccess,
                                             DeviceCategory effectiveDeviceCategory) {
        return runsDocker || effectiveSshAccess || !effectiveDeviceCategory.isAppliance();
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
            peer.machineId(),
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
            peer.sshAccess(),
            client == null ? 0L : client.goodbyeEpoch()
        );
    }

    /**
     * Projects a LAN server into a {@code Machine}. {@code anchorLanCidr} is the CIDR of the
     * relay peer (or Vaier server) that routes to it, or null when no anchor covers it.
     */
    public static Machine fromLanServer(LanServer server, String anchorLanCidr) {
        return new Machine(
            server.machineId(),
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
    public static Machine vaierServer(MachineId id, Boolean sshAccessOverride) {
        return new Machine(
            id, LanAnchor.VAIER_SERVER_NAME, MachineType.UBUNTU_SERVER,
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
            transferRx, transferTx, goodbyeEpoch).isConnected();
    }

    /**
     * Whether this machine should be answering and is not — a server, never a personal device that is
     * simply away. A LAN server nobody has probed yet is {@link Reachability#UNKNOWN}, which is not a verdict.
     */
    public boolean isDown(Map<String, Reachability> lanReachability) {
        if (type == MachineType.LAN_SERVER) {
            return lanReachability != null && lanReachability.get(lanAddress) == Reachability.DOWN;
        }
        return type != null && type.isServerType() && !isReachable(lanReachability);
    }


    /**
     * Whether Vaier can open a shell on this machine — and therefore whether anything that needs one can
     * happen here at all: delivering a fleet credential, signing its Claude CLI in, running a command. True only when the operator allows Vaier SSH here <em>and</em> Vaier holds a login for it,
     * which is what {@code hasHostCredential} answers (the credential vault is not this type's to read).
     *
     * <p>It lives on the machine because it is a fact about a machine rather than about any one feature,
     * and because the alternative — each fleet-wide operation keeping its own copy of a two-clause rule —
     * is two chances to get it subtly wrong. A machine that fails it is never an error: a phone has
     * nowhere to put a file or run a shell, and a machine with no host credential is one Vaier is not
     * permitted to open a session to.
     */
    public boolean runsAShellVaierCanReach(boolean hasHostCredential) {
        return effectiveSshAccess() && hasHostCredential;
    }

    /**
     * What to call the machine {@code machineId} in something a person reads — an admin email, the
     * recovery sheet — given the name the fleet currently has for it, if any.
     *
     * <p>Records are keyed by identity so a rename cannot orphan them, which means a record can outlive the
     * machine it points at. What to say then is a decision, not a formatting detail: it must be
     * distinguishable from a real name (or an operator reads past it), and it must still carry the id (or an
     * operator who notices cannot act on it). It lives here because it was being answered four different
     * ways — in the recovery sheet, in two admin alerts and in a REST DTO.
     *
     * <p>Not for machine-readable fields. A DTO or an SSE payload wants {@code null} or the raw id, never
     * this prose; those callers deliberately do not come here.
     */
    public static String labelFor(MachineId machineId, Optional<String> name) {
        return name.filter(n -> !n.isBlank()).orElseGet(() ->
            "a machine no longer in this fleet (" + (machineId == null ? "unknown id" : machineId.value()) + ")");
    }
}
