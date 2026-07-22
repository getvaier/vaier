package net.vaier.domain;

/**
 * The result of probing a single LAN address the operator typed while adding a machine by hand — the
 * manual-add counterpart of the scanner's per-host {@link DiscoveredLanMachine}. Vaier resolves what
 * routes to the address ({@link LanAnchor}) and, when something answers, probes its service ports so
 * the Add form can offer the same detected readout and SSH credential test the adopt flow does.
 *
 * <p>{@code reachable} is false — with a null {@link #host} and {@link #routedVia} — when no anchor
 * covers the address or nothing answered, so the caller simply falls back to the manual fields rather
 * than treating it as an error. When reachable, the derivations an operator relies on (open ports,
 * SSH availability, the Docker port, the device-category guess) all live on the carried
 * {@link DiscoveredLanMachine}, so this value object holds no logic of its own.
 *
 * @param reachable  whether an anchor covered the address and the host answered the probe
 * @param host       the probed host with its open ports and domain read-offs, or {@code null} when not reachable
 * @param routedVia  the relay peer (or the Vaier server) the probe was routed through, or {@code null}
 */
public record LanHostProbe(boolean reachable, DiscoveredLanMachine host, String routedVia) {

    /** Nothing routes to the address, or nothing answered: fall back to the manual fields. */
    public static LanHostProbe notReachable() {
        return new LanHostProbe(false, null, null);
    }

    /** A host answered on {@code routedVia}'s LAN: carry it plus which anchor reached it. */
    public static LanHostProbe reached(DiscoveredLanMachine host, String routedVia) {
        return new LanHostProbe(true, host, routedVia);
    }
}
