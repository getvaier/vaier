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

    /**
     * Public launchpad listing. Returns only services that anyone is allowed to see — never
     * auth-protected ones (issue #207). The Authelia bypass set must include this path so an
     * anonymous browser load of {@code /launchpad.html} can fetch it without being redirected
     * to the login portal.
     */
    @GetMapping("/services")
    public List<LaunchpadServiceUco> getServices(HttpServletRequest request) {
        return getLaunchpadServicesUseCase.getLaunchpadServices(resolveCallerIp(request), false);
    }

    /**
     * Authenticated launchpad listing. Includes auth-protected tiles. The path must NOT be in
     * Authelia's bypass set — it falls through to {@code one_factor}, so reaching this method
     * is itself proof that the caller holds a valid session. The launchpad page tries this
     * endpoint first and falls back to {@link #getServices} on a 401/302 redirect.
     */
    @GetMapping("/services-authenticated")
    public List<LaunchpadServiceUco> getServicesAuthenticated(HttpServletRequest request) {
        return getLaunchpadServicesUseCase.getLaunchpadServices(resolveCallerIp(request), true);
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
