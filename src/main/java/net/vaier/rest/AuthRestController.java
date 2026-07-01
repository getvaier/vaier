package net.vaier.rest;

import net.vaier.config.ConfigResolver;
import net.vaier.domain.VaierHostnames;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthRestController {

    private final ConfigResolver configResolver;

    public AuthRestController(ConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    @GetMapping("/users/me")
    public ResponseEntity<MeResponse> getMe(
            @RequestHeader(value = "Remote-User", required = false) String username,
            @RequestHeader(value = "Remote-Name", required = false) String displayname,
            @RequestHeader(value = "Remote-Email", required = false) String email) {
        String domain = configResolver.getDomain();
        boolean hasDomain = domain != null && !domain.isBlank();
        // The console is always on social login, so a session is always ended via oauth2-proxy's
        // sign-out, which clears the domain-wide SSO cookie. The login link
        // just points back at the console; hitting it unauthenticated triggers the Google sign-in.
        VaierHostnames hostnames = new VaierHostnames(domain);
        String console = hasDomain ? "https://" + hostnames.vaierServerFqdn() + "/" : null;
        String logoutUrl = hasDomain ? hostnames.oauth2SignOutUrl(console) : null;
        String loginUrl = console;
        return ResponseEntity.ok(new MeResponse(username, displayname, email, logoutUrl, loginUrl));
    }

    public record MeResponse(String username, String displayname, String email, String logoutUrl, String loginUrl) {}
}
