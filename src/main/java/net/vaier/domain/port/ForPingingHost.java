package net.vaier.domain.port;

public interface ForPingingHost {

    /**
     * ICMP echo probe used as a fallback when TCP port probes all time out — printers,
     * IoT devices, and IPMI cards often respond to ping without exposing any of the ports
     * the reachability scheduler tries.
     *
     * @return {@code true} iff at least one ICMP echo reply came back inside the timeout.
     */
    boolean isReachable(String host, int timeoutMs);
}
