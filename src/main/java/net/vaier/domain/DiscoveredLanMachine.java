package net.vaier.domain;

import java.util.Collection;
import java.util.List;

/**
 * A host the LAN scanner (issue #246) found responding on a relay peer's LAN (or the Vaier
 * server's own LAN). It is a <em>candidate</em> the operator can register as a {@link LanServer}
 * with one click — not a persisted entity. The domain owns the read-offs an operator and the UI
 * both rely on: what the host probably is, whether it is already registered, and the stable key
 * used to ignore it.
 *
 * @param ipAddress    the responsive LAN IP
 * @param hostname     a resolved hostname, or {@code null} when none was discoverable
 * @param openPorts    the probed service ports the host answered on
 * @param relayAnchor  the relay peer id whose LAN this host sits on, or
 *                     {@link LanAnchor#VAIER_SERVER_NAME} for the Vaier server's own LAN
 */
public record DiscoveredLanMachine(
    String ipAddress,
    String hostname,
    List<Integer> openPorts,
    String relayAnchor
) {
    /** The advisory role guessed from {@link #openPorts}. */
    public LanMachineRole guessedRole() {
        return LanMachineRole.fromOpenPorts(openPorts);
    }

    /**
     * True when a registered machine already owns this host's address — drop it from the
     * candidates. The claimed addresses span every registered machine type: LAN servers and
     * VPN peers (relays and Ubuntu servers carry a LAN address), so a host already on the map
     * never resurfaces as a candidate.
     */
    public boolean isAlreadyRegistered(Collection<String> registeredAddresses) {
        return registeredAddresses.contains(ipAddress);
    }

    /**
     * The stable key the operator's ignore-list is keyed on: the relay anchor plus the address.
     * Independent of hostname and open ports so ignoring a host survives the next scan finding it
     * with a different snapshot.
     */
    public String ignoreKey() {
        return relayAnchor + "|" + ipAddress;
    }
}
