package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForInitialisingVpnRouting;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@Component
@Slf4j
public class VpnNetworkSetupAdapter implements ForInitialisingVpnRouting {

    @Value("${wireguard.container.name:wireguard}")
    private String wireguardContainerName;

    @Value("${wireguard.vpn.subnet:10.13.13.0/24}")
    private String vpnSubnet;

    private final HostnameResolver hostnameResolver;
    private final ProcessRunner processRunner;

    public VpnNetworkSetupAdapter() {
        this(new InetAddressHostnameResolver(), new ProcessBuilderRunner());
    }

    VpnNetworkSetupAdapter(HostnameResolver hostnameResolver, ProcessRunner processRunner) {
        this.hostnameResolver = hostnameResolver;
        this.processRunner = processRunner;
    }

    @Override
    public void setupVpnRouting() {
        try {
            String wireguardIp = hostnameResolver.resolve(wireguardContainerName);

            ProcessResult result = processRunner.run(
                List.of("ip", "route", "add", vpnSubnet, "via", wireguardIp));

            if (result.exitCode() == 0) {
                log.info("Added VPN route: {} via {}", vpnSubnet, wireguardIp);
            } else if (result.output().contains("File exists")) {
                log.info("VPN route already exists: {} via {}", vpnSubnet, wireguardIp);
            } else {
                log.warn("Failed to add VPN route (exit {}): {}", result.exitCode(), result.output());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while setting up VPN routing");
        } catch (Exception e) {
            log.warn("Could not set up VPN routing: {}", e.getMessage());
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
