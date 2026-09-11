package net.vaier.domain.port;

import net.vaier.domain.ReverseProxyConfig;

/**
 * Driven port for reading back the whole of Vaier's generated Traefik dynamic config — every router,
 * service and middleware it declares, unflattened.
 *
 * <p>Separate from {@link ForPersistingReverseProxyRoutes} deliberately. That port speaks in
 * {@link net.vaier.domain.ReverseProxyRoute}s — a router and its service seen as one publishable thing,
 * with the middlewares collapsed into an auth mode — and that projection is exactly what let #354 hide: an
 * orphaned middleware belongs to no route, so it was invisible to every read Vaier had. This is a
 * different conversation with the same file, and the {@link net.vaier.domain.ReverseProxyAudit} is its only
 * caller.
 *
 * <p>Read-only, and by design: the audit reports, it never repairs.
 */
public interface ForReadingReverseProxyConfig {

    /** The config as it stands. A missing file reads as {@link ReverseProxyConfig#empty()}, never an error. */
    ReverseProxyConfig getReverseProxyConfig();
}
