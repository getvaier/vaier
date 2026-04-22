package net.vaier.rest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import net.vaier.application.GetLaunchpadServicesUseCase;
import net.vaier.application.GetLaunchpadServicesUseCase.LaunchpadServiceUco;
import net.vaier.domain.Cidr;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        if (Cidr.parse(trustedProxyCidr).contains(remote)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma < 0 ? xff : xff.substring(0, comma)).trim();
            }
        }
        return remote;
    }
}
