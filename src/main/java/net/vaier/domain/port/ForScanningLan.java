package net.vaier.domain.port;

import java.util.List;

/**
 * Driven port that sweeps a LAN CIDR and reports the hosts that respond. The driven side owns the
 * probe mechanism (an ICMP/TCP sweep run from the Vaier WireGuard container, which already routes
 * to every relay peer's LAN) and the parsing of its output. Callers get back only responsive
 * hosts — an empty list when nothing answered or the CIDR is unreachable.
 *
 * <p>An Enterprise-only capability (issue #246): the scan is intrusive, so it runs only when the
 * application explicitly asks and the licence permits it.
 */
public interface ForScanningLan {

    /** The hosts in {@code cidr} that answered the probe. */
    List<ScannedHost> scan(String cidr);

    /**
     * A single responsive host.
     *
     * @param ipAddress the host's LAN address
     * @param openPorts the probed service ports it answered on (may be empty for a ping-only hit)
     * @param hostname  a resolved hostname, or {@code null} when none was discoverable
     */
    record ScannedHost(String ipAddress, List<Integer> openPorts, String hostname) {}
}
