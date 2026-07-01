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
        // The logout URL is resolved through the mode-aware domain helper driven by the console's own
        // auth mode (VAIER_CONSOLE_AUTH_MODE): when the console runs on social login (#305 step 3b) it
        // ends the session via oauth2-proxy's sign-out; otherwise it uses the Authelia portal logout.
        VaierHostnames hostnames = new VaierHostnames(domain);
        String console = hasDomain ? "https://" + hostnames.vaierServerFqdn() + "/" : null;
        String logoutUrl = hasDomain ? hostnames.logoutUrl(configResolver.getConsoleAuthMode(), console) : null;
        String loginUrl = hasDomain ? "https://" + hostnames.autheliaHost() + "/?rd=" + console : null;
        return ResponseEntity.ok(new MeResponse(username, displayname, email, logoutUrl, loginUrl));
    }

    public record MeResponse(String username, String displayname, String email, String logoutUrl, String loginUrl) {}
}
