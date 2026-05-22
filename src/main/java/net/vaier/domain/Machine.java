package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;

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
    Integer dockerPort
) {

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
            null
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
            server.dockerPort()
        );
    }
}
