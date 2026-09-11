package net.vaier.domain;

import lombok.Builder;

import java.util.List;

/**
 * The whole of Vaier's generated Traefik dynamic config — {@code remote-apps.yml} — as Vaier reads it back:
 * every router, service and middleware it declares, by name, with the few fields the
 * {@link ReverseProxyAudit reverse proxy invariants} need.
 *
 * <p>Distinct from {@link ReverseProxyRoute}, which is the <em>projection</em> the rest of Vaier works in: a
 * route is a router and its service seen as one publishable thing, and the middlewares are flattened into an
 * {@link AuthMode}. That projection is exactly why #354 could happen — an orphaned middleware, or a service
 * nothing routes to, is not <em>part</em> of any route, so it fell out of every read Vaier had and four
 * broken entries sat in the file unseen. This type is the unflattened view, and it exists for the audit
 * alone.
 *
 * <p>Entries are keyed by protocol as well as by name, because Traefik keeps {@code http:} and {@code tcp:}
 * in separate namespaces — a stream's router must be judged against the stream services, never against the
 * HTTP ones.
 */
@Builder
public record ReverseProxyConfig(List<ConfiguredRouter> routers,
                                 List<ConfiguredService> services,
                                 List<ConfiguredMiddleware> middlewares) {

    public ReverseProxyConfig {
        routers = routers == null ? List.of() : List.copyOf(routers);
        services = services == null ? List.of() : List.copyOf(services);
        middlewares = middlewares == null ? List.of() : List.copyOf(middlewares);
    }

    /** No config at all — what a missing file reads as, and never an error. */
    public static ReverseProxyConfig empty() {
        return new ReverseProxyConfig(List.of(), List.of(), List.of());
    }

    /** Which of Traefik's two namespaces an entry lives in. Its own name is how a finding says it. */
    public enum Protocol {
        HTTP,
        TCP
    }

    /**
     * One router: what it serves and what it carries.
     *
     * @param serviceName     the service it points at, or null when it names none at all
     * @param middlewareNames the middlewares it references, in chain order
     */
    @Builder
    public record ConfiguredRouter(Protocol protocol, String name, String serviceName,
                                   List<String> middlewareNames) {
        public ConfiguredRouter {
            middlewareNames = middlewareNames == null ? List.of() : List.copyOf(middlewareNames);
        }
    }

    /** One service. Only its name matters to the audit — where it points is the route's business. */
    public record ConfiguredService(Protocol protocol, String name) { }

    /**
     * One middleware, with the two things an invariant can be broken by.
     *
     * @param redirectRegex       a {@code redirectRegex}'s pattern, or null when it is not a redirect
     * @param redirectReplacement where that redirect sends the request
     * @param errorsServiceName   the service an {@code errors} middleware serves its pages from — a real
     *                            reference, so a service reached only this way is not stranded
     */
    @Builder
    public record ConfiguredMiddleware(Protocol protocol, String name, String redirectRegex,
                                       String redirectReplacement, String errorsServiceName) { }
}
