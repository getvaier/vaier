package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;

import java.util.Optional;

/**
 * Which machine an incoming caller <em>is</em> — the one question the tunnel, and only the tunnel, can
 * answer. WireGuard authenticated the packet before anything in Vaier saw it, so a source address inside
 * the VPN subnet cannot be forged and the peer holding it is the caller.
 *
 * <p><b>Both halves are the rule, not one plus an optional extra.</b> An address must sit in the VPN
 * subnet <em>and</em> be held by a known peer. Dropping the subnet check would leave the peer store as the
 * sole judge, and a mobile carrier address — shared by thousands of subscribers — that happened to match a
 * peer record would then name a device it has nothing to do with. Vaier would rather know nothing.
 *
 * <p>Everything that must not guess a device's identity from an address asks here: the forward-auth check
 * behind {@link LastServiceReached}, and position reporting, whose whole trust model rests on the tunnel
 * outranking a claim cookie.
 */
public final class TunnelCaller {

    private TunnelCaller() {
    }

    /**
     * The machine calling from {@code callerIp}, or empty for every address that is not a peer's tunnel IP
     * — which is the ordinary answer for the whole public internet.
     *
     * <p>The subnet check runs first because it is also the cheap one: this sits on the path that
     * authenticates every request to every gated service, and an address outside the tunnel cannot be a
     * peer's tunnel IP, so the peer store is never asked about one. A subnet that will not parse is a
     * misconfiguration, and the safe answer to "is this caller on the tunnel?" is then no.
     */
    public static Optional<MachineId> machineFor(String callerIp, String vpnSubnet,
                                                 ForGettingPeerConfigurations peers) {
        return peerFor(callerIp, vpnSubnet, peers).map(PeerConfiguration::machineId);
    }

    /** The peer behind {@link #machineFor}, for a caller that needs more of it than its identity. */
    public static Optional<PeerConfiguration> peerFor(String callerIp, String vpnSubnet,
                                                      ForGettingPeerConfigurations peers) {
        if (peers == null || !onTheTunnel(callerIp, vpnSubnet)) {
            return Optional.empty();
        }
        return peers.getPeerConfigByIp(callerIp);
    }

    private static boolean onTheTunnel(String callerIp, String vpnSubnet) {
        if (callerIp == null || callerIp.isBlank() || vpnSubnet == null) return false;
        try {
            return Cidr.parse(vpnSubnet).contains(callerIp);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
