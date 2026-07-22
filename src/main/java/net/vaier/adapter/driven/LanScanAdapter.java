package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.Cidr;
import net.vaier.domain.port.ForExecutingInContainer;
import net.vaier.domain.port.ForScanningLan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sweeps a LAN CIDR from inside the Vaier WireGuard container — the one host that already routes to
 * every relay peer's LAN over the tunnel (routes installed by {@code LanRouteAdapter}). A narrow
 * TCP-connect sweep over common service ports keeps the probe quick and non-intrusive; each
 * responsive host is emitted as {@code ip|csvOpenPorts|hostname} and parsed back into a
 * {@link ScannedHost}. Per-host probes are deliberately not logged at INFO (issue #246: privacy).
 *
 * <p>First slice supports a {@code /24}-style sweep (the common relay-LAN shape): the network's
 * first three octets are swept {@code .1–.254}. Larger CIDRs are under-scanned to that range rather
 * than fanning out to thousands of hosts.
 */
@Component
@Slf4j
public class LanScanAdapter implements ForScanningLan {

    /** Service ports probed per host; the open set feeds {@link net.vaier.domain.LanMachineRole}. */
    private static final List<Integer> PROBE_PORTS = List.of(22, 80, 443, 2375, 2376, 5000, 8080, 8443, 9100);

    private final ForExecutingInContainer executor;
    private final String wireguardContainerName;

    public LanScanAdapter(ForExecutingInContainer executor,
                          @Value("${wireguard.container.name:wireguard}") String wireguardContainerName) {
        this.executor = executor;
        this.wireguardContainerName = wireguardContainerName;
    }

    @Override
    public List<ScannedHost> scan(String cidr) {
        String prefix = networkPrefix(cidr);
        if (prefix == null) {
            log.warn("Skipping LAN scan for an unsupported CIDR");
            return List.of();
        }
        try {
            // execute() runs argv-style (no shell re-wrapping), so the multi-line sweep is passed
            // verbatim as the single argument to `bash -c`. executeWithInput() would re-join the
            // argv through `echo … | bash -c`, mangling the script's newlines and $() — don't use it.
            String output = executor.execute(wireguardContainerName, "bash", "-c", sweepScript(prefix));
            return parseScanOutput(output);
        } catch (RuntimeException e) {
            log.warn("LAN scan failed for a relay LAN: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * The same TCP port sweep aimed at a single address rather than a whole CIDR — the manual "add a
     * LAN server by address" helper inspects the one host the operator named. Runs the identical probe
     * (same {@link #PROBE_PORTS}, same {@code /dev/tcp} + ping liveness, same output format) from inside
     * the WireGuard container so it routes to relay LANs, and reuses {@link #parseScanOutput}. Empty for
     * a non-IPv4 address, an exec failure, or a host that answered nothing.
     */
    @Override
    public Optional<ScannedHost> scanHost(String ipAddress) {
        if (ipAddress == null || !Cidr.isIpv4(ipAddress.trim())) {
            return Optional.empty();
        }
        String ip = ipAddress.trim();
        try {
            String output = executor.execute(wireguardContainerName, "bash", "-c", probeHostScript(ip));
            return parseScanOutput(output).stream().findFirst();
        } catch (RuntimeException e) {
            log.warn("Single-host LAN probe failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Builds the bash probe for one host: try a TCP connect to each probe port (each bounded by
     * {@code timeout 1} so an unreachable host can't hang the probe), then fall back to a ping for a
     * liveness-only hit. Emits the same {@code ip|csvOpenPorts|hostname} line the CIDR sweep does, so
     * {@link #parseScanOutput} parses it identically.
     */
    private String probeHostScript(String ip) {
        String ports = PROBE_PORTS.stream().map(String::valueOf).reduce((a, b) -> a + " " + b).orElse("");
        return """
            ip=%s
            open=""
            for p in %s; do
              timeout 1 bash -c "echo > /dev/tcp/$ip/$p" >/dev/null 2>&1 && open="${open:+$open,}$p"
            done
            alive=0
            [ -n "$open" ] && alive=1
            if [ "$alive" = 0 ]; then ping -c1 -W1 $ip >/dev/null 2>&1 && alive=1; fi
            if [ "$alive" = 1 ]; then
              host=$(getent hosts $ip 2>/dev/null | awk '{print $2}')
              echo "$ip|$open|$host"
            fi
            """.formatted(ip, ports);
    }

    /** {@code "192.168.3.0/24" → "192.168.3."}; null for an invalid CIDR. */
    private static String networkPrefix(String cidr) {
        if (cidr == null) return null;
        String base = cidr.split("/")[0];
        if (!Cidr.isIpv4(base)) return null;
        return base.substring(0, base.lastIndexOf('.') + 1);
    }

    /**
     * Builds the bash sweep. For each host {@code prefix.1–254} it tries a TCP connect to each probe
     * port via {@code /dev/tcp}, each bounded by {@code timeout 1} so an unreachable host over the
     * tunnel (no RST, no route) can't hang the sweep on SYN retransmit. A host with at least one open
     * port (or a successful ping) is printed as {@code ip|csvOpenPorts|hostname}. Hosts are probed in
     * parallel and joined with {@code wait}, so the whole /24 finishes in a few seconds.
     */
    private String sweepScript(String prefix) {
        String ports = PROBE_PORTS.stream().map(String::valueOf).reduce((a, b) -> a + " " + b).orElse("");
        return """
            for n in $(seq 1 254); do (
              ip=%s$n
              open=""
              for p in %s; do
                timeout 1 bash -c "echo > /dev/tcp/$ip/$p" >/dev/null 2>&1 && open="${open:+$open,}$p"
              done
              alive=0
              [ -n "$open" ] && alive=1
              if [ "$alive" = 0 ]; then ping -c1 -W1 $ip >/dev/null 2>&1 && alive=1; fi
              if [ "$alive" = 1 ]; then
                host=$(getent hosts $ip 2>/dev/null | awk '{print $2}')
                echo "$ip|$open|$host"
              fi
            ) & done
            wait
            """.formatted(prefix, ports);
    }

    /**
     * Parses the sweep output: one {@code ip|csvOpenPorts|hostname} line per host. Blank lines, a
     * non-IPv4 first field, and non-numeric ports are dropped. An empty hostname field becomes
     * {@code null}; an empty ports field becomes an empty list (a ping-only hit).
     */
    static List<ScannedHost> parseScanOutput(String raw) {
        List<ScannedHost> hosts = new ArrayList<>();
        if (raw == null || raw.isBlank()) return hosts;
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\\|", -1);
            if (parts.length < 3) continue;
            String ip = parts[0].trim();
            if (!Cidr.isIpv4(ip)) continue;
            hosts.add(new ScannedHost(ip, parsePorts(parts[1]), blankToNull(parts[2])));
        }
        return hosts;
    }

    private static List<Integer> parsePorts(String csv) {
        List<Integer> ports = new ArrayList<>();
        if (csv == null || csv.isBlank()) return ports;
        for (String p : csv.split(",")) {
            try {
                ports.add(Integer.parseInt(p.trim()));
            } catch (NumberFormatException ignored) {
                // a malformed port token — skip it
            }
        }
        return ports;
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
