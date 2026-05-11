package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;

import java.util.List;
import java.util.Optional;

/**
 * Identifies what routes packets to a given LAN IP address: a <em>relay peer</em> whose
 * {@code lanCidr} contains it, or — failing that — the Vaier server itself, when the address
 * falls inside the Vaier server's own LAN CIDR ({@code ForResolvingServerLanCidr}).
 *
 * <p>When both a relay peer and the server LAN CIDR cover the address the relay peer wins: its
 * forwarding is already configured, so re-routing under it would be surprising. A LAN server or
 * LAN service anchored at the Vaier server is reachable directly from the Vaier-side containers
 * (vaier / traefik → docker bridge → host → the host's LAN/VPC NIC), so no relay peer is needed.
 */
public final class LanAnchor {

    /** Canonical display name for the Vaier server itself, used wherever a peer name would otherwise appear. */
    public static final String VAIER_SERVER_NAME = "Vaier server";

    private final PeerConfiguration relayPeer; // null ⇒ the Vaier server
    private final String cidr;

    private LanAnchor(PeerConfiguration relayPeer, String cidr) {
        this.relayPeer = relayPeer;
        this.cidr = cidr;
    }

    public static Optional<LanAnchor> resolve(String lanAddress, List<PeerConfiguration> peers, String serverLanCidr) {
        for (PeerConfiguration p : peers) {
            if (covers(p.lanCidr(), lanAddress)) return Optional.of(new LanAnchor(p, p.lanCidr()));
        }
        if (covers(serverLanCidr, lanAddress)) return Optional.of(new LanAnchor(null, serverLanCidr));
        return Optional.empty();
    }

    public boolean isVaierServer() {
        return relayPeer == null;
    }

    /** The relay peer when {@link #isVaierServer()} is false; otherwise empty. */
    public Optional<PeerConfiguration> relayPeer() {
        return Optional.ofNullable(relayPeer);
    }

    /** Relay peer name, or {@link #VAIER_SERVER_NAME} when anchored at the Vaier server. */
    public String name() {
        return relayPeer == null ? VAIER_SERVER_NAME : relayPeer.name();
    }

    /** The CIDR — a relay peer's {@code lanCidr} or the server LAN CIDR — that covers the address. */
    public String cidr() {
        return cidr;
    }

    private static boolean covers(String cidr, String ip) {
        if (cidr == null || cidr.isBlank()) return false;
        try {
            return Cidr.parse(cidr).contains(ip);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
