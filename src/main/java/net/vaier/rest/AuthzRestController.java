package net.vaier.rest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.AssignGroupsUseCase;
import net.vaier.application.GetServiceAccessRulesUseCase;
import net.vaier.application.GrantRoleUseCase;
import net.vaier.application.ListAccessEntriesUseCase;
import net.vaier.application.RecordAllowedAccessUseCase;
import net.vaier.application.RevokeAccessUseCase;
import net.vaier.application.SetServiceAccessRuleUseCase;
import net.vaier.application.VerifyAccessUseCase;
import net.vaier.domain.AccessDecision;
import net.vaier.domain.CallerIp;
import net.vaier.domain.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Social-login authorization (Option C). Hosts two distinct surfaces:
 *
 * <ul>
 *   <li>{@code GET /authz/verify} — the Traefik forward-auth endpoint. oauth2-proxy authenticates
 *       the user (Google) and injects {@code X-Auth-Request-Email}; this endpoint answers whether
 *       that email may reach {@code X-Forwarded-Host}, emitting the downstream identity headers.
 *       <p><b>Traefik wiring (later step):</b> when oauth2-proxy + the {@code vaier-authz}
 *       middleware are wired in {@code docker-compose.yml}, this exact path must be reachable
 *       <em>without</em> the Vaier console's own auth middleware (it is the auth check itself).
 *       Do NOT make any of the {@code /access*} admin endpoints anonymous — they stay behind the
 *       existing console auth.</li>
 *   <li>{@code GET/PATCH/DELETE /access...} — admin management of access entries, authenticated
 *       behind the existing Vaier console auth.</li>
 * </ul>
 */
@RestController
@Slf4j
public class AuthzRestController {

    /**
     * The same trusted-proxy boundary the launchpad uses, and deliberately the same property: this is the
     * identical question — which hop of {@code X-Forwarded-For} came from Vaier's own Traefik — so an
     * operator whose Docker bridge differs should change one value, not hunt for a second one. (Contrast
     * {@code security.docker-bridge-cidr}, which holds the same number today for a genuinely different
     * reason and is kept separate on purpose.)
     *
     * <p>{@code vaier.trusted-proxy-cidr} rather than {@code launchpad.*} because the boundary now gates
     * forward-auth too, and nobody should have to edit a launchpad key to fix who authenticates their
     * fleet. The old key stays as the fallback: an operator who already set it is not broken by the rename.
     */
    @Value("${vaier.trusted-proxy-cidr:${launchpad.trusted-proxy-cidr:172.20.0.0/16}}")
    private String trustedProxyCidr;

    private final VerifyAccessUseCase verifyAccessUseCase;
    private final ListAccessEntriesUseCase listAccessEntriesUseCase;
    private final GrantRoleUseCase grantRoleUseCase;
    private final AssignGroupsUseCase assignGroupsUseCase;
    private final RevokeAccessUseCase revokeAccessUseCase;
    private final SetServiceAccessRuleUseCase setServiceAccessRuleUseCase;
    private final GetServiceAccessRulesUseCase getServiceAccessRulesUseCase;
    private final RecordAllowedAccessUseCase recordAllowedAccessUseCase;

    public AuthzRestController(VerifyAccessUseCase verifyAccessUseCase,
                              ListAccessEntriesUseCase listAccessEntriesUseCase,
                              GrantRoleUseCase grantRoleUseCase,
                              AssignGroupsUseCase assignGroupsUseCase,
                              RevokeAccessUseCase revokeAccessUseCase,
                              SetServiceAccessRuleUseCase setServiceAccessRuleUseCase,
                              GetServiceAccessRulesUseCase getServiceAccessRulesUseCase,
                              RecordAllowedAccessUseCase recordAllowedAccessUseCase) {
        this.verifyAccessUseCase = verifyAccessUseCase;
        this.listAccessEntriesUseCase = listAccessEntriesUseCase;
        this.grantRoleUseCase = grantRoleUseCase;
        this.assignGroupsUseCase = assignGroupsUseCase;
        this.revokeAccessUseCase = revokeAccessUseCase;
        this.setServiceAccessRuleUseCase = setServiceAccessRuleUseCase;
        this.getServiceAccessRulesUseCase = getServiceAccessRulesUseCase;
        this.recordAllowedAccessUseCase = recordAllowedAccessUseCase;
    }

    // --- Forward-auth (data path) ---

    @GetMapping("/authz/verify")
    public ResponseEntity<String> verify(
            HttpServletRequest request,
            @RequestHeader(value = "X-Auth-Request-Email", required = false) String email,
            @RequestHeader(value = "X-Forwarded-Host", required = false) String host,
            @RequestHeader(value = "X-Auth-Request-Name", required = false) String name,
            @RequestHeader(value = "X-Auth-Request-Connector", required = false) String provider,
            @RequestHeader(value = "X-Auth-Request-Connector-Uid", required = false) String providerUserId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String presentedAuthorization) {
        AccessDecision decision = verifyAccessUseCase.verify(email, host, name, provider, providerUserId);
        if (!decision.isAllowed()) {
            // Traefik forward-auth returns this body to the browser on a non-2xx, so a denied
            // (pending or not-in-group) identity sees the branded "awaiting approval" page.
            return ResponseEntity.status(403).contentType(MediaType.TEXT_HTML).body(DENIED_PAGE);
        }
        recordAllowedAccess(request, decision, host);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok()
                .header("Remote-User", decision.getUser())
                .header("Remote-Email", decision.getEmail())
                .header("Remote-Groups", decision.groupsHeader());
        // Pre-approved entries have no display name yet — only forward Remote-Name once known, so we
        // never emit an empty header for a nameless identity.
        String displayName = decision.getName();
        if (displayName != null && !displayName.isBlank()) {
            ok.header("Remote-Name", displayName);
        }
        decision.authorizationFor(presentedAuthorization)
                .ifPresent(authorization -> ok.header(HttpHeaders.AUTHORIZATION, authorization));
        return ok.body(null);
    }

    /**
     * Note where this allowed access came from, so the map can draw a green dot for the place, and which
     * service was reached, so a machine's row can say what it last opened. Only on an allow: a refused
     * knock belongs to the threat side of the security view, not to this one.
     *
     * <p>{@code host} is the requested service, and this endpoint is the only place in Vaier that sees it.
     * Whether the caller identifies a machine at all is decided downstream, in the domain — a carrier
     * address names a person and never a device.
     *
     * <p><b>Nothing in here may reach the response.</b> This endpoint is what authenticates every request
     * to every gated service, so a geolocation database that has gone missing, a store that cannot be
     * written, or a bug nobody has found yet must cost a dot on a map and never an operator's access to
     * their own fleet. The use case promises the same thing on its own side; this guard is the one that
     * has to hold when that promise is broken, which is why the test for it asserts on the 200.
     */
    private void recordAllowedAccess(HttpServletRequest request, AccessDecision decision, String host) {
        try {
            CallerIp callerIp = CallerIp.of(request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"), trustedProxyCidr);
            recordAllowedAccessUseCase.recordAllowedAccess(callerIp.value(), decision.getEmail(), host,
                Instant.now());
        } catch (Exception e) {
            log.debug("Not recording this allowed access: {}", e.getMessage());
        }
    }

    /** The branded "awaiting approval" page, loaded once from the classpath. */
    private static final String DENIED_PAGE = loadDeniedPage();

    private static String loadDeniedPage() {
        try (InputStream in = AuthzRestController.class.getResourceAsStream("/authz-denied.html")) {
            if (in == null) {
                return "<!DOCTYPE html><title>Awaiting approval · Vaier</title>"
                        + "<h1>Awaiting approval</h1><p>Ask an administrator to grant you access.</p>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<!DOCTYPE html><title>Awaiting approval · Vaier</title>"
                    + "<h1>Awaiting approval</h1><p>Ask an administrator to grant you access.</p>";
        }
    }

    // --- Admin management (behind the console's existing auth) ---

    @GetMapping("/access")
    public List<AccessEntryResponse> listAccess() {
        return listAccessEntriesUseCase.listAccessEntries().stream()
                .map(e -> new AccessEntryResponse(e.getEmail(), e.getRole().wireValue(),
                        e.getGroups() != null ? e.getGroups() : List.of(), e.getName(), e.getProvider(),
                        e.getProviderUserId()))
                .toList();
    }

    @PatchMapping("/access/{email}/role")
    public ResponseEntity<String> grantRole(@PathVariable String email, @RequestBody GrantRoleRequest request) {
        grantRoleUseCase.grantRole(email, Role.fromString(request.role()));
        return ResponseEntity.ok("Role updated successfully");
    }

    @PatchMapping("/access/{email}/groups")
    public ResponseEntity<String> assignGroups(@PathVariable String email, @RequestBody AssignGroupsRequest request) {
        assignGroupsUseCase.assignGroups(email, request.groups());
        return ResponseEntity.ok("Groups updated successfully");
    }

    @DeleteMapping("/access/{email}")
    public ResponseEntity<String> revokeAccess(@PathVariable String email) {
        revokeAccessUseCase.revokeAccess(email);
        return ResponseEntity.ok("Access revoked successfully");
    }

    // --- Per-service access rules (admin) ---

    @GetMapping("/access/services")
    public Map<String, List<String>> getServiceAccessRules() {
        return getServiceAccessRulesUseCase.getServiceAccessRules();
    }

    @PutMapping("/access/services/{host}/groups")
    public ResponseEntity<String> setServiceAccessRule(@PathVariable String host,
                                                       @RequestBody ServiceAccessRuleRequest request) {
        List<String> groups = request.groups() != null ? request.groups() : List.of();
        setServiceAccessRuleUseCase.setAllowedGroups(host, groups);
        return ResponseEntity.ok("Service access rule updated successfully");
    }

    public record AccessEntryResponse(String email, String role, List<String> groups, String name, String provider,
                                      String providerUserId) {}
    public record GrantRoleRequest(String role) {}
    public record AssignGroupsRequest(List<String> groups) {}
    public record ServiceAccessRuleRequest(List<String> groups) {}
}
