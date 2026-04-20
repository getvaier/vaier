package net.vaier.rest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import net.vaier.application.GetLaunchpadServicesUseCase;
import net.vaier.application.GetLaunchpadServicesUseCase.LaunchpadServiceUco;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.util.List;

@RestController
@RequestMapping("/launchpad")
@RequiredArgsConstructor
public class LaunchpadRestController {

    private final GetLaunchpadServicesUseCase getLaunchpadServicesUseCase;

    @Value("${launchpad.trusted-proxy-cidr:172.20.0.0/16}")
    private String trustedProxyCidr;

    @GetMapping("/services")
    public List<LaunchpadServiceUco> getServices(HttpServletRequest request) {
        return getLaunchpadServicesUseCase.getLaunchpadServices(resolveCallerIp(request));
    }

    String resolveCallerIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (inCidr(remote, trustedProxyCidr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma < 0 ? xff : xff.substring(0, comma)).trim();
            }
        }
        return remote;
    }

    static boolean inCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            byte[] target = InetAddress.getByName(ip).getAddress();
            if (network.length != target.length) return false;
            int prefix = Integer.parseInt(parts[1]);
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (network[i] != target[i]) return false;
            }
            if (remainingBits == 0) return true;
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (network[fullBytes] & mask) == (target[fullBytes] & mask);
        } catch (Exception e) {
            return false;
        }
    }
}
