package net.vaier.rest;

import net.vaier.application.ResolveViewerUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.VaierHostnames;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
public class AuthRestController {

    private final ConfigResolver configResolver;
    private final ResolveViewerUseCase resolveViewerUseCase;

    public AuthRestController(ConfigResolver configResolver, ResolveViewerUseCase resolveViewerUseCase) {
        this.configResolver = configResolver;
        this.resolveViewerUseCase = resolveViewerUseCase;
    }

    /**
     * The signed-in identity, for the launchpad topbar. Served behind the identity-optional router
     * (oauth2-authn only), so it is reached only with a valid session and the identity arrives in
     * oauth2-proxy's {@code X-Auth-Request-*} headers — an anonymous caller is stopped by
     * oauth2-authn with a 401 before ever reaching this method (the launchpad treats that as
     * anonymous). The resolved {@link AccessEntry} decides {@code isAdmin}, which gates the admin
     * nav; an authenticated but pending/unknown identity is a non-admin viewer.
     */
    @GetMapping("/users/me")
    public ResponseEntity<MeResponse> getMe(
            @RequestHeader(value = "X-Auth-Request-User", required = false) String username,
            @RequestHeader(value = "X-Auth-Request-Name", required = false) String displayname,
            @RequestHeader(value = "X-Auth-Request-Email", required = false) String email) {
        Optional<AccessEntry> viewer = resolveViewerUseCase.resolveViewer(email);
        boolean isAdmin = viewer.map(AccessEntry::isAdmin).orElse(false);
        String resolvedName = firstNonBlank(displayname, viewer.map(AccessEntry::getName).orElse(null), username);

        String domain = configResolver.getDomain();
        boolean hasDomain = domain != null && !domain.isBlank();
        // The console is always on social login, so a session is always ended via oauth2-proxy's
        // sign-out, which clears the domain-wide SSO cookie. The login link just points back at the
        // console; hitting it unauthenticated triggers the Google sign-in.
        VaierHostnames hostnames = new VaierHostnames(domain);
        String console = hasDomain ? "https://" + hostnames.vaierServerFqdn() + "/" : null;
        String logoutUrl = hasDomain ? hostnames.oauth2SignOutUrl(console) : null;
        String loginUrl = console;
        return ResponseEntity.ok(
            new MeResponse(username, resolvedName, email, isAdmin, logoutUrl, loginUrl));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    public record MeResponse(String username, String displayname, String email, boolean isAdmin,
                             String logoutUrl, String loginUrl) {}
}
