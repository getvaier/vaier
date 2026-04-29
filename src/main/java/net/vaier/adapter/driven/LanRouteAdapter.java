package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForSyncingLanRoutes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class LanRouteAdapter implements ForSyncingLanRoutes {

    @Value("${wireguard.container.name:wireguard}")
    private String wireguardContainerName;

    @Value("${wireguard.vpn.subnet:10.13.13.0/24}")
    private String vpnSubnet;

    private final HostnameResolver hostnameResolver;
    private final ProcessRunner processRunner;

    public LanRouteAdapter() {
        this(new InetAddressHostnameResolver(), new ProcessBuilderRunner());
    }

    LanRouteAdapter(HostnameResolver hostnameResolver, ProcessRunner processRunner) {
        this.hostnameResolver = hostnameResolver;
        this.processRunner = processRunner;
    }

    @Override
    public void syncLanRoutes(Set<String> desiredCidrs) {
        String wireguardIp;
        try {
            wireguardIp = hostnameResolver.resolve(wireguardContainerName);
        } catch (UnknownHostException e) {
            log.warn("Cannot resolve wireguard container '{}' — skipping LAN route sync: {}",
                wireguardContainerName, e.getMessage());
            return;
        }

        Set<String> existing = listRoutesVia(wireguardIp);

        for (String cidr : desiredCidrs) {
            run(List.of("ip", "route", "replace", cidr, "via", wireguardIp), "install LAN route " + cidr);
        }

        for (String stale : existing) {
            // The VPN subnet route is installed by VpnNetworkSetupAdapter and shares the same gateway —
            // never delete it from here, even when it doesn't appear in desiredCidrs.
            if (stale.equals(vpnSubnet)) continue;
            if (!desiredCidrs.contains(stale)) {
                run(List.of("ip", "route", "del", stale), "remove stale LAN route " + stale);
            }
        }
    }

    private Set<String> listRoutesVia(String wireguardIp) {
        try {
            ProcessResult result = processRunner.run(List.of("ip", "route", "show"));
            if (result.exitCode() != 0) {
                log.warn("ip route show failed (exit {}): {}", result.exitCode(), result.output());
                return Set.of();
            }
            // Each line is like "192.168.3.0/24 via 172.20.0.2 dev eth0"; pick CIDRs whose gateway is the WG container.
            Set<String> matched = new HashSet<>();
            for (String line : result.output().split("\\R")) {
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length >= 3 && "via".equals(tokens[1]) && wireguardIp.equals(tokens[2]) && tokens[0].contains("/")) {
                    matched.add(tokens[0]);
                }
            }
            return matched;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Set.of();
        } catch (Exception e) {
            log.warn("Could not list current routes: {}", e.getMessage());
            return Set.of();
        }
    }

    private void run(List<String> command, String description) {
        try {
            ProcessResult result = processRunner.run(command);
            if (result.exitCode() == 0) {
                log.info("Did {}: {}", description, String.join(" ", command));
            } else {
                log.warn("Failed to {} (exit {}): {}", description, result.exitCode(), result.output());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while trying to {}", description);
        } catch (Exception e) {
            log.warn("Could not {}: {}", description, e.getMessage());
        }
    }

    @FunctionalInterface
    interface HostnameResolver {
        String resolve(String hostname) throws UnknownHostException;
    }

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command) throws IOException, InterruptedException;
    }

    record ProcessResult(int exitCode, String output) {}

    private static class InetAddressHostnameResolver implements HostnameResolver {
        @Override
        public String resolve(String hostname) throws UnknownHostException {
            return InetAddress.getByName(hostname).getHostAddress();
        }
    }

    private static class ProcessBuilderRunner implements ProcessRunner {
        @Override
        public ProcessResult run(List<String> command) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            return new ProcessResult(exitCode, output);
        }
    }
}
