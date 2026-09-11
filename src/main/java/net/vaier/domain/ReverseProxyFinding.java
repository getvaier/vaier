package net.vaier.domain;

/**
 * One defect the {@link ReverseProxyAudit} found in Vaier's own generated reverse proxy config: which entry, what
 * kind of wrong, and a sentence an operator can act on.
 *
 * <p>The sentence is the whole point. #354 was found by an operator meeting {@code ERR_TOO_MANY_REDIRECTS}
 * on a URL and working backwards; "the middleware {@code x} redirects to a URL its own pattern matches" is
 * that same conclusion, arrived at without the puzzling afternoon.
 *
 * @param kind      which invariant was broken
 * @param entryName the router, service or middleware at fault — the one an operator would go looking for
 * @param message   one sentence naming the entry and what is wrong with it
 */
public record ReverseProxyFinding(Kind kind, String entryName, String message) {

    /**
     * The five invariants of #354. Declaration order is report order, so the findings an operator reads —
     * and the mail they get — are stable between sweeps.
     */
    public enum Kind {
        /** An {@code http.middlewares} entry no router names. Left behind, typically by an unpublish. */
        UNREFERENCED_MIDDLEWARE,
        /** A router naming a middleware that does not exist. Traefik refuses the route outright. */
        DANGLING_MIDDLEWARE_REFERENCE,
        /** A {@code redirectRegex} whose replacement its own pattern matches — a redirect loop. */
        SELF_REFERENTIAL_REDIRECT,
        /** A router naming no service, or naming one that is not defined. */
        ROUTER_WITHOUT_SERVICE,
        /** A service no router and no middleware sends anything to. */
        UNROUTED_SERVICE
    }

    public ReverseProxyFinding {
        if (kind == null) {
            throw new IllegalArgumentException("A finding needs a kind");
        }
        if (entryName == null || entryName.isBlank()) {
            throw new IllegalArgumentException("A finding needs the name of the entry at fault");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("A finding needs a sentence an operator can act on");
        }
    }

    /** How this finding is identified across sweeps — the same entry broken the same way is not news. */
    public String signature() {
        return kind.name() + ":" + entryName;
    }
}
