package net.vaier.domain;

import net.vaier.config.ServiceNames;

import java.util.List;
import java.util.Locale;

/**
 * How a published route is gated. Replaces the per-route "requires auth" boolean so the two
 * forward-auth gateways can coexist while services migrate one at a time (#305):
 *
 * <ul>
 *   <li>{@link #NONE} — public, no auth middleware.</li>
 *   <li>{@link #AUTHELIA} — today's behaviour: the single {@code auth-middleware} forward-auth to
 *       Authelia. The backward-compatible default.</li>
 *   <li>{@link #SOCIAL} — the proven step-1 two-stage chain: serve the oauth2-proxy sign-in page on
 *       401 ({@code oauth2-signin}), authenticate with Google ({@code oauth2-authn}), then authorize
 *       against Vaier's access store ({@code vaier-authz}).</li>
 * </ul>
 *
 * The decision of <em>which middleware chain a route needs</em> lives here, on the mode — the
 * Traefik adapter only translates the names into YAML.
 */
public enum AuthMode {
    NONE,
    AUTHELIA,
    SOCIAL;

    /** The lowercase token surfaced over the wire and used in patches. */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse a wire token. Unknown, blank or null values read as {@link #AUTHELIA} — the safe
     * default, so a malformed value never silently drops authentication from a protected service.
     */
    public static AuthMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return AUTHELIA;
        }
        try {
            return AuthMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AUTHELIA;
        }
    }

    /** Map the legacy {@code requiresAuth} toggle: {@code true} → Authelia, {@code false} → none. */
    public static AuthMode fromRequiresAuth(boolean requiresAuth) {
        return requiresAuth ? AUTHELIA : NONE;
    }

    /** Whether this is the social-login mode (needs oauth2-proxy + the per-host {@code /oauth2/} router). */
    public boolean isSocial() {
        return this == SOCIAL;
    }

    /**
     * The ordered auth-middleware names this mode prepends to a router's middleware chain. The
     * redirect and offline-page middlewares are appended by the adapter after these.
     */
    public List<String> authMiddlewareNames() {
        return switch (this) {
            case NONE -> List.of();
            case AUTHELIA -> List.of(ServiceNames.AUTH_MIDDLEWARE);
            case SOCIAL -> List.of(ServiceNames.OAUTH2_SIGNIN_MIDDLEWARE,
                                   ServiceNames.OAUTH2_AUTHN_MIDDLEWARE,
                                   ServiceNames.VAIER_AUTHZ_MIDDLEWARE);
        };
    }

    /**
     * Every auth-middleware name any mode can emit. A mode switch strips all of these from the
     * router before prepending the new mode's chain, so no stale links from the prior mode remain.
     */
    public static List<String> allAuthMiddlewareNames() {
        return List.of(ServiceNames.AUTH_MIDDLEWARE,
                       ServiceNames.OAUTH2_SIGNIN_MIDDLEWARE,
                       ServiceNames.OAUTH2_AUTHN_MIDDLEWARE,
                       ServiceNames.VAIER_AUTHZ_MIDDLEWARE);
    }

    /**
     * Read the mode back off a router's middleware list. Social wins when its forward-auth links are
     * present, then Authelia, else none — so the published-services API and the UI picker reflect
     * what the route actually carries.
     */
    public static AuthMode fromMiddlewareNames(List<String> middlewares) {
        if (middlewares == null) {
            return NONE;
        }
        if (middlewares.contains(ServiceNames.OAUTH2_AUTHN_MIDDLEWARE)
                || middlewares.contains(ServiceNames.VAIER_AUTHZ_MIDDLEWARE)) {
            return SOCIAL;
        }
        if (middlewares.contains(ServiceNames.AUTH_MIDDLEWARE)) {
            return AUTHELIA;
        }
        return NONE;
    }
}
