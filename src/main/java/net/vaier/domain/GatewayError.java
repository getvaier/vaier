package net.vaier.domain;

/**
 * The business decision behind Vaier's <b>offline page</b>: how an upstream gateway status code
 * (502/503/504, or anything else Traefik routes to the error service) maps to the human-readable
 * title and message a visitor sees when a published service's backend is unreachable.
 *
 * <p>This mapping is a domain decision, not a presentation detail — the controller and the Traefik
 * adapter only carry the status around; what each status <em>means</em> to a visitor lives here.
 */
public record GatewayError(int status, String title, String message) {

    /**
     * Map an HTTP status to its offline-page copy. 502/503/504 each get tailored wording; any other
     * status falls back to the generic "unavailable" copy (the same family as a 502) while keeping
     * its own status code so the page can still be served with the original status.
     */
    public static GatewayError forStatus(int status) {
        return switch (status) {
            case 503 -> new GatewayError(503,
                "Service temporarily unavailable",
                "This service is up but not ready to serve requests just yet. "
                    + "Give it a moment and try again.");
            case 504 -> new GatewayError(504,
                "Service timed out",
                "This service took too long to respond. It may be busy or unavailable. "
                    + "Try again in a moment.");
            default -> new GatewayError(status,
                "Service unavailable",
                "This service is currently unavailable — its backend can't be reached right now. "
                    + "It may be starting up, restarting, or temporarily down.");
        };
    }
}
