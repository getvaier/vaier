package net.vaier.domain;

import java.util.List;

/**
 * A best-effort guess at what a discovered LAN machine is, derived from the service ports it
 * answers on. Purely advisory — it pre-fills the role hint when an operator registers the machine,
 * never a hard classification. The guess priority (Docker &gt; web UI &gt; SSH &gt; printer) lives
 * here so the scanner service and any UI agree on the single rule.
 */
public enum LanMachineRole {
    DOCKER_HOST,
    WEB_UI,
    SSH_HOST,
    PRINTER,
    UNKNOWN;

    private static final List<Integer> DOCKER_PORTS = List.of(2375, 2376);
    private static final List<Integer> WEB_PORTS = List.of(80, 443, 8080, 8443, 5000);
    private static final List<Integer> PRINTER_PORTS = List.of(9100, 631, 515);

    /** The most specific role the open ports imply; {@link #UNKNOWN} when none is telling. */
    public static LanMachineRole fromOpenPorts(List<Integer> openPorts) {
        if (openPorts == null) return UNKNOWN;
        if (containsAny(openPorts, DOCKER_PORTS)) return DOCKER_HOST;
        if (containsAny(openPorts, WEB_PORTS)) return WEB_UI;
        if (openPorts.contains(22)) return SSH_HOST;
        if (containsAny(openPorts, PRINTER_PORTS)) return PRINTER;
        return UNKNOWN;
    }

    private static boolean containsAny(List<Integer> ports, List<Integer> wanted) {
        return wanted.stream().anyMatch(ports::contains);
    }
}
