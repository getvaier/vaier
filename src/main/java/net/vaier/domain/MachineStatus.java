package net.vaier.domain;

/**
 * Four-state machine health for the vpn-peers page. Computed in the domain so the browser only
 * maps a status value to a colour — it never combines multiple signals.
 *
 * <ul>
 *   <li>{@link #UNKNOWN} — no probe has run yet (grey)</li>
 *   <li>{@link #OK}      — the host is on the network and any auxiliary scrape passed (green)</li>
 *   <li>{@link #DEGRADED}— the host is on the network but a secondary signal (Docker scrape) is
 *                          failing (yellow)</li>
 *   <li>{@link #DOWN}    — the host is unreachable (red)</li>
 * </ul>
 */
public enum MachineStatus {
    UNKNOWN, OK, DEGRADED, DOWN;

    /**
     * The four-state status of a LAN server. {@code reachabilityKnown=false} → UNKNOWN; the host
     * not being on the network → DOWN; a Docker-enabled host whose scrape is failing → DEGRADED;
     * otherwise OK. Non-Docker hosts never degrade — they have no secondary signal to flunk.
     */
    public static MachineStatus forLanServer(boolean reachabilityKnown, boolean reachable,
                                             boolean runsDocker, boolean dockerScrapeOk) {
        if (!reachabilityKnown) return UNKNOWN;
        if (!reachable) return DOWN;
        if (!runsDocker) return OK;
        return dockerScrapeOk ? OK : DEGRADED;
    }
}
