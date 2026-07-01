package net.vaier.rest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import net.vaier.application.GetLaunchpadServicesUseCase;
import net.vaier.application.GetLaunchpadServicesUseCase.LaunchpadServiceUco;
import net.vaier.application.ResolveViewerUseCase;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.Cidr;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/launchpad")
@RequiredArgsConstructor
public class LaunchpadRestController {

    private final GetLaunchpadServicesUseCase getLaunchpadServicesUseCase;
    private final ResolveViewerUseCase resolveViewerUseCase;

    @Value("${launchpad.trusted-proxy-cidr:172.20.0.0/16}")
    private String trustedProxyCidr;

    /**
     * Public launchpad listing. Returns only public (auth mode NONE) services — never social-gated
     * ones. This path stays anonymously reachable so a logged-out browser load of the launchpad can
     * fetch it without being redirected to sign in.
     */
    @GetMapping("/services")
    public List<LaunchpadServiceUco> getServices(HttpServletRequest request) {
        return getLaunchpadServicesUseCase.getLaunchpadServices(resolveCallerIp(request), (AccessEntry) null);
    }

    /**
     * Viewer-adaptive launchpad listing. Served behind the identity-optional router (oauth2-authn
     * only): a valid session arrives with {@code X-Auth-Request-Email}, which resolves to the
     * viewer's {@link AccessEntry} so the listing includes exactly the social services that identity
     * may reach. An anonymous caller is stopped by oauth2-authn with a 401 before reaching here; the
     * launchpad page then falls back to {@link #getServices}. An authenticated-but-unknown/pending
     * identity resolves to no viewer and so sees public services only.
     */
    @GetMapping("/services-authenticated")
    public List<LaunchpadServiceUco> getServicesAuthenticated(
            HttpServletRequest request,
            @RequestHeader(value = "X-Auth-Request-Email", required = false) String email) {
        AccessEntry viewer = resolveViewerUseCase.resolveViewer(email).orElse(null);
        return getLaunchpadServicesUseCase.getLaunchpadServices(resolveCallerIp(request), viewer);
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
