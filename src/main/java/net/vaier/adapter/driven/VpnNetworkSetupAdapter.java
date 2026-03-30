package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForInitialisingVpnRouting;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

@Component
@Slf4j
public class VpnNetworkSetupAdapter implements ForInitialisingVpnRouting {

    @Value("${wireguard.container.name:wireguard}")
    private String wireguardContainerName;

    @Value("${wireguard.vpn.subnet:10.13.13.0/24}")
    private String vpnSubnet;

    @Override
    public void setupVpnRouting() {
        try {
            String wireguardIp = InetAddress.getByName(wireguardContainerName).getHostAddress();

            Process process = new ProcessBuilder("ip", "route", "add", vpnSubnet, "via", wireguardIp)
                .redirectErrorStream(true)
                .start();

            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("Added VPN route: {} via {}", vpnSubnet, wireguardIp);
            } else if (output.contains("File exists")) {
                log.info("VPN route already exists: {} via {}", vpnSubnet, wireguardIp);
            } else {
                log.warn("Failed to add VPN route (exit {}): {}", exitCode, output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while setting up VPN routing");
        } catch (Exception e) {
            log.warn("Could not set up VPN routing: {}", e.getMessage());
        }
    }
}
