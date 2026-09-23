package net.vaier.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import net.vaier.config.ServiceNames;
import net.vaier.domain.Server.State;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForProbingServiceSignIn;
import net.vaier.domain.port.ForProbingServiceVersion;
import net.vaier.domain.port.ForResolvingPeerIds;
import net.vaier.domain.port.ForResolvingServiceGroup;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Getter
@ToString
public class ReverseProxyRoute {

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;

    /** The port a stream is dialled on — the TLS entrypoint, where the HostSNI rule is read. */
    public static final int STREAM_PORT = 443;
    /** What a stream speaks, where a route records a protocol. Not http, so never fetched from. */
    public static final String STREAM_PROTOCOL = "tcp";

    private static final Pattern PATH_PREFIX_PATTERN = Pattern.compile("^/[A-Za-z0-9._\\-]+(/[A-Za-z0-9._\\-]+)*/?$");
    /** One DNS label: lowercase alphanumerics and inner hyphens, 1–63 characters (RFC 1035 §2.3.1). */
    private static final Pattern SUBDOMAIN_LABEL_PATTERN =
        Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    private final String name;
    private final String domainName;
    private final String address;
    private final int port;
    private final String service;
    private final AuthInfo authInfo;
    private final List<String> entryPoints;
    private final TlsConfig tlsConfig;
    private final List<String> middlewares;
    private final String rootRedirectPath;
    private final boolean directUrlDisabled;
    private final boolean isLanService;
    private final String protocol;
    private final String pathPrefix;
    private final boolean hiddenFromLaunchpad;
    private final String launchpadAlias;
    private final String versionEndpoint;
    private final String versionProperty;
    /** True when this is a TCP stream (a Traefik {@code tcp:} router matched by {@code HostSNI}). */
    private final boolean stream;

    /** Call sites use the builder: four consecutive booleans here are two silent swaps waiting to happen. */
    @Builder(toBuilder = true)
    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig,
                             List<String> middlewares, String rootRedirectPath, boolean directUrlDisabled,
                             boolean isLanService, String protocol, String pathPrefix,
                             boolean hiddenFromLaunchpad, String launchpadAlias,
                             String versionEndpoint, String versionProperty, boolean stream) {
        this.name = name;
        this.domainName = domainName;
        this.address = address;
        this.port = port;
        this.service = service;
        this.authInfo = authInfo;
        this.entryPoints = entryPoints;
        this.tlsConfig = tlsConfig;
        this.middlewares = middlewares;
        this.rootRedirectPath = rootRedirectPath;
        this.directUrlDisabled = directUrlDisabled;
        this.isLanService = isLanService;
        this.protocol = protocol;
        this.pathPrefix = pathPrefix;
        this.hiddenFromLaunchpad = hiddenFromLaunchpad;
        this.launchpadAlias = launchpadAlias;
        this.versionEndpoint = versionEndpoint;
        this.versionProperty = versionProperty;
        this.stream = stream;
    }

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig,
                             List<String> middlewares, String rootRedirectPath, boolean directUrlDisabled,
                             boolean isLanService, String protocol, String pathPrefix,
                             boolean hiddenFromLaunchpad, String launchpadAlias) {
        this(name, domainName, address, port, service, authInfo, entryPoints, tlsConfig, middlewares,
             rootRedirectPath, directUrlDisabled, isLanService, protocol, pathPrefix, hiddenFromLaunchpad,
             launchpadAlias, null, null, false);
    }

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig,
                             List<String> middlewares, String rootRedirectPath, boolean directUrlDisabled,
                             boolean isLanService, String protocol, String pathPrefix,
                             boolean hiddenFromLaunchpad) {
        this(name, domainName, address, port, service, authInfo, entryPoints, tlsConfig, middlewares,
             rootRedirectPath, directUrlDisabled, isLanService, protocol, pathPrefix, hiddenFromLaunchpad, null);
    }

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig,
                             List<String> middlewares, String rootRedirectPath, boolean directUrlDisabled,
                             boolean isLanService, String protocol, String pathPrefix) {
        this(name, domainName, address, port, service, authInfo, entryPoints, tlsConfig, middlewares,
             rootRedirectPath, directUrlDisabled, isLanService, protocol, pathPrefix, false, null);
    }

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig,
                             List<String> middlewares, String rootRedirectPath, boolean directUrlDisabled,
                             boolean isLanService, String protocol) {
        this(name, domainName, address, port, service, authInfo, entryPoints, tlsConfig, middlewares,
             rootRedirectPath, directUrlDisabled, isLanService, protocol, null, false, null);
    }

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig,
                             List<String> middlewares, String rootRedirectPath, boolean directUrlDisabled) {
        this(name, domainName, address, port, service, authInfo, entryPoints, tlsConfig, middlewares,
             rootRedirectPath, directUrlDisabled, false, null, null, false, null);
    }

    public static ReverseProxyRoute lanRoute(String name, String domainName, String host, int port,
                                             String protocol, String service) {
        return new ReverseProxyRoute(name, domainName, host, port, service, null, null, null, null,
            null, false, true, protocol, null, false, null);
    }

    public static void validateForPublication(String dnsName, String address, int port) {
        validateDnsName(dnsName);
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("address must not be blank");
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(
                "port must be between " + MIN_PORT + " and " + MAX_PORT + " (was " + port + ")");
        }
    }

    /** What a stream publication may carry: no login, no path, no redirect. */
    public static void validateStreamPublication(AuthMode authMode, String pathPrefix,
                                                 String rootRedirectPath) {
        validateStreamAuthMode(authMode);
        // HostSNI matches the host and nothing else, and a stream has no URL to redirect.
        if (pathPrefix != null && !pathPrefix.isBlank()) {
            throw new IllegalArgumentException("A stream is matched by host name only — it cannot take a path prefix");
        }
        if (rootRedirectPath != null && !rootRedirectPath.isBlank()) {
            throw new IllegalArgumentException("A stream is not a URL — it cannot take a root redirect");
        }
    }

    /** A per-route setting the operator can change after publishing, named as the operator would say it. */
    public enum RouteSetting {
        AUTH_MODE("a login"),
        ROOT_REDIRECT("a root redirect"),
        DIRECT_URL("a direct LAN link"),
        LAUNCHPAD("a launchpad tile"),
        VERSION_PROBE("a version probe");

        private final String description;

        RouteSetting(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /**
     * Refuse the settings this route cannot carry. A stream carries none of them: every one is an HTTP
     * idea — a login to redirect to, a URL to redirect from, a tile to link, a page to read a version off —
     * and there is no HTTP inside a stream. Left unguarded these reached the adapter, which looks for the
     * router among the HTTP ones and answers "Router not found" (a 500 for what is a bad request), or wrote
     * a setting nothing would ever read.
     */
    public void validateUpdate(Set<RouteSetting> requested) {
        if (!stream) return;
        requested.stream().findFirst().ifPresent(setting -> {
            throw new IllegalArgumentException(
                "A stream cannot have " + setting.description() + ": nothing inside a TCP stream is an "
                + "HTTP request. Its own credentials are its only gate, and it has no URL to configure.");
        });
    }

    private static void validateStreamAuthMode(AuthMode requested) {
        if (requested != null && requested.isSocial()) {
            throw new IllegalArgumentException(
                "A stream cannot require a login: nothing inside a TCP stream is an HTTP request, so "
                + "oauth2-proxy has nothing to gate. The service's own password is its only gate.");
        }
    }

    /**
     * Where a client dials this stream — the published name on the TLS port. Null for an HTTP route,
     * which is reached at a URL rather than at a host and port.
     */
    public String connectAddress() {
        return stream ? domainName + ":" + STREAM_PORT : null;
    }

    /**
     * The operator's subdomain, held to what a DNS label actually is. It is interpolated into a Traefik
     * rule, and in a stream's {@code HostSNI(`...`)} a stray backtick does not break the rule — it WIDENS
     * it: close the quote, add a matcher, and one TCP router on 443 answers for every name the box serves,
     * the console included. May carry dots (a machine-qualified name like {@code printer.colina27}); each
     * label between them is checked on its own.
     */
    public static void validateSubdomain(String subdomain) {
        if (subdomain == null || subdomain.isBlank()) {
            throw new IllegalArgumentException("subdomain must not be blank");
        }
        for (String label : subdomain.split("\\.", -1)) {
            if (!SUBDOMAIN_LABEL_PATTERN.matcher(label).matches()) {
                throw new IllegalArgumentException(
                    "subdomain must be lowercase letters, digits and hyphens, in dot-separated labels that "
                    + "neither start nor end with a hyphen (was: " + subdomain + ")");
            }
        }
    }

    public static void validateDnsName(String dnsName) {
        if (dnsName == null || dnsName.isBlank()) {
            throw new IllegalArgumentException("dnsName must not be blank");
        }
    }

    /**
     * Normalises operator-supplied path prefixes. Null, blank, and "/" all collapse to null (= no
     * PathPrefix, i.e. the route catches everything on its host). An operator-typed trailing
     * slash is preserved — backend SPAs sometimes serve different content for {@code /path} vs
     * {@code /path/}, so the slash is part of the operator's intent. If the operator wants both
     * {@code /auth} and {@code /auth/} to match, they type {@code /auth} (no slash) — Traefik's
     * {@code PathPrefix("/auth")} then catches both shapes.
     */
    public static String normalisePathPrefix(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equals("/")) return null;
        return trimmed;
    }

    /**
     * The path a bare host redirects to, or null when there is no redirect. Blank and {@code /} both mean
     * "no redirect": Traefik's root rule matches the host with or without the trailing slash, so a
     * replacement of the host itself re-matches its own trigger and the browser loops until it gives up.
     */
    public static String normaliseRootRedirectPath(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equals("/")) return null;
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    /**
     * Validates an already-normalised path prefix. Null is allowed (means "no PathPrefix"). Anything
     * else must start with {@code /}, contain no whitespace or URL-reserved characters, and have at
     * least one alphanumeric/-/_/. character after the leading slash.
     */
    public static void validatePathPrefix(String pathPrefix) {
        if (pathPrefix == null) return;
        if (!PATH_PREFIX_PATTERN.matcher(pathPrefix).matches()) {
            throw new IllegalArgumentException(
                "pathPrefix must start with '/' and contain only letters, digits, '-', '_', '.', and '/' " +
                "(was: " + pathPrefix + ")");
        }
    }

    /** Normalises a publish protocol: a blank value defaults to {@code http}; otherwise trimmed and lowercased. */
    public static String normaliseProtocol(String raw) {
        return (raw == null || raw.isBlank()) ? "http" : raw.trim().toLowerCase();
    }

    /** Validates an already-normalised protocol. Only {@code http} and {@code https} are allowed. */
    public static void validateProtocol(String protocol) {
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new IllegalArgumentException("protocol must be http or https (was " + protocol + ")");
        }
    }

    // --- domain rules over a list of existing routes ---

    /**
     * True iff any route in {@code existing} already targets the given backend {@code address}
     * and {@code port} — used to drop a container from the publishable-services list once it has
     * been published.
     */
    public static boolean hasRouteFor(List<ReverseProxyRoute> existing, String address, int port) {
        return existing.stream().anyMatch(r -> r.address.equals(address) && r.port == port);
    }

    /**
     * True iff any of {@code endpoints} is already routed. One service can be reachable at more than one
     * address+port — a container on Vaier's own network that also publishes a host port has two spellings
     * of the same thing — and *already published* is a question about the service, not about which
     * spelling happened to be written into the route.
     */
    public static boolean hasRouteForAny(List<ReverseProxyRoute> existing,
                                         List<DockerService.ServiceEndpoint> endpoints) {
        return endpoints.stream().anyMatch(ep -> hasRouteFor(existing, ep.address(), ep.port()));
    }

    /**
     * True iff any route in {@code existing} shares both the FQDN and the (already-normalised)
     * pathPrefix — i.e. publishing on top of it would be a duplicate that Traefik couldn't
     * disambiguate. Null pathPrefix matches another null pathPrefix (two host-only routes
     * collide).
     */
    public static boolean conflictsWithExisting(List<ReverseProxyRoute> existing, String fqdn,
                                                String pathPrefix) {
        return existing.stream().anyMatch(r ->
            fqdn.equals(r.getDomainName()) && Objects.equals(pathPrefix, r.getPathPrefix()));
    }

    /**
     * Find the route with the given FQDN + pathPrefix in {@code existing}. Used by delete flows to
     * resolve a user-facing (fqdn, pathPrefix) tuple into a specific routerName.
     */
    public static Optional<ReverseProxyRoute> findByFqdnAndPath(List<ReverseProxyRoute> existing,
                                                                          String fqdn, String pathPrefix) {
        return existing.stream()
            .filter(r -> fqdn.equals(r.getDomainName()) && Objects.equals(pathPrefix, r.getPathPrefix()))
            .findFirst();
    }

    /**
     * True iff this route is one Vaier persists in its own Traefik dynamic-config file and can
     * therefore delete. File-backed router names are plain slugs (e.g. {@code pump-router}); routes
     * surfaced only from the Traefik API carry a {@code name@provider} suffix (e.g. {@code whoami@docker},
     * {@code dashboard@internal}) and have no file entry — deleting one throws "Router not found".
     * Cascade cleanup (peer / LAN-server deletion) filters on this so an unrelated Docker-label route
     * that happens to share a backend address can't abort the deletion.
     */
    public boolean isVaierManaged() {
        return name != null && !name.contains("@");
    }

    /**
     * How this route is gated, read off its Traefik middleware chain — {@link AuthMode#SOCIAL} when
     * the oauth2-proxy links are present, else {@link AuthMode#NONE}. The published-services API and
     * the UI auth-mode picker read this so they reflect the route's actual gateway rather than
     * re-deriving it.
     */
    public AuthMode authMode() {
        return AuthMode.fromMiddlewareNames(middlewares);
    }

    /**
     * True when this is the per-host {@code Host(...) && PathPrefix(/oauth2/)} helper router that a
     * social-gated route needs so oauth2-proxy's sign-in/callback/sign-out endpoints are reachable
     * without auth. It is infrastructure, not a published service — the published-services view and
     * the launchpad filter it out, and it is named {@code <host-with-dashes>-oauth2-router}.
     */
    public boolean isOauth2EndpointsRouter() {
        return name != null && name.endsWith("-oauth2-router");
    }

    /** The router key of the {@code /oauth2/} helper router for {@code dnsName}. */
    public static String oauth2EndpointsRouterName(String dnsName) {
        return dnsName.replace(".", "-") + "-oauth2-router";
    }

    /**
     * The chain a new HTTP route is written with: the CrowdSec bouncer first, so a banned address is
     * refused before anything else runs; then the auth mode's links, the root redirect when there is one,
     * and the offline page last. Every route Vaier writes carries the bouncer — only the recovery doors,
     * Vaier's own sign-in path declared in {@code docker-compose.yml}, go without it.
     */
    public static List<String> middlewareChain(AuthMode authMode, String redirectMiddlewareName) {
        List<String> chain = new ArrayList<>(authMode.authMiddlewareNames());
        if (redirectMiddlewareName != null) chain.add(redirectMiddlewareName);
        chain.add(ServiceNames.ERROR_PAGES_MIDDLEWARE);
        return withBouncerFirst(chain);
    }

    /** {@code existing} re-gated for {@code authMode}: every mode's auth links swapped for this mode's. */
    public static List<String> rechained(List<String> existing, AuthMode authMode) {
        List<String> rest = new ArrayList<>(existing);
        rest.removeAll(AuthMode.allAuthMiddlewareNames());
        List<String> chain = new ArrayList<>(authMode.authMiddlewareNames());
        chain.addAll(rest);
        return withBouncerFirst(chain);
    }

    /**
     * {@code existing} as the domain writes a chain today — the bouncer at its head and the offline page
     * at its tail, each once, the rest untouched. How a route published before either existed gets them.
     */
    public static List<String> upToDateChain(List<String> existing) {
        List<String> chain = new ArrayList<>(existing);
        if (!chain.contains(ServiceNames.ERROR_PAGES_MIDDLEWARE)) chain.add(ServiceNames.ERROR_PAGES_MIDDLEWARE);
        return withBouncerFirst(chain);
    }

    private static List<String> withBouncerFirst(List<String> existing) {
        List<String> chain = new ArrayList<>(existing);
        chain.removeIf(ServiceNames.CROWDSEC_BOUNCER_MIDDLEWARE::equals);
        chain.addFirst(ServiceNames.CROWDSEC_BOUNCER_MIDDLEWARE);
        return chain;
    }

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service, AuthInfo authInfo) {
        this(name, domainName, address, port, service, authInfo, null, null, null, null, false);
    }

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig, List<String> middlewares) {
        this(name, domainName, address, port, service, authInfo, entryPoints, tlsConfig, middlewares, null, false);
    }

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig, List<String> middlewares,
                             String rootRedirectPath) {
        this(name, domainName, address, port, service, authInfo, entryPoints, tlsConfig, middlewares, rootRedirectPath, false);
    }

    /**
     * Consolidated launchpad-rendering state. Owns every reason a route may be hidden, inactive,
     * or active so the launchpad use case stays a thin pass-through: new visibility rules accrete
     * here, not in the application layer. Three outcomes:
     * <ul>
     *   <li>{@link LaunchpadVisibility#NOT_VISIBLE} — operator hid it, or DNS is not propagated
     *       (the tile would link to a non-resolving host).</li>
     *   <li>{@link LaunchpadVisibility#VISIBLE_INACTIVE} — the backend is currently unreachable;
     *       render the tile but visually de-emphasised.</li>
     *   <li>{@link LaunchpadVisibility#VISIBLE_ACTIVE} — DNS propagated, backend healthy.</li>
     * </ul>
     */
    /**
     * The label the launchpad tile should display for this route. Precedence:
     * <ol>
     *   <li>operator-supplied {@code launchpadAlias} (non-blank) — wins always;</li>
     *   <li>final segment of {@code pathPrefix} — for path-based routes the path is the
     *       human-meaningful part (e.g. {@code /grafana} → {@code "grafana"});</li>
     *   <li>first DNS label otherwise — {@code grafana.example.com} → {@code "grafana"}.</li>
     * </ol>
     * {@code baseDomain} is accepted for symmetry with other display helpers; currently unused
     * because the first-label rule doesn't need it. Kept so future rules (e.g. multi-label
     * sub-domains) don't have to thread it back in.
     */
    public String launchpadDisplayName(String baseDomain) {
        if (launchpadAlias != null && !launchpadAlias.isBlank()) return launchpadAlias.trim();
        if (pathPrefix != null) {
            String trimmed = pathPrefix.endsWith("/") && pathPrefix.length() > 1
                ? pathPrefix.substring(0, pathPrefix.length() - 1)
                : pathPrefix;
            return trimmed.substring(trimmed.lastIndexOf('/') + 1);
        }
        return domainName.split("\\.")[0];
    }

    /**
     * What to call the host somebody reached: the {@link #launchpadDisplayName} of the route serving it,
     * or null when Vaier publishes no route for that host — the console is one such host, and a name Vaier
     * cannot support is worse than the host standing on its own.
     *
     * <p>A host-only route wins over a path-scoped sibling on the same host: a bare host name is what a
     * path-less route serves, and the sibling's label describes a path nobody said was reached.
     */
    public static String launchpadDisplayNameFor(List<ReverseProxyRoute> routes, String host,
                                                 String baseDomain) {
        if (routes == null || host == null || host.isBlank()) return null;
        ReverseProxyRoute best = null;
        for (ReverseProxyRoute route : routes) {
            if (route == null || !host.equalsIgnoreCase(route.getDomainName())) continue;
            if (route.getPathPrefix() == null) return route.launchpadDisplayName(baseDomain);
            if (best == null) best = route;
        }
        return best == null ? null : best.launchpadDisplayName(baseDomain);
    }

    /**
     * The query string the launchpad should send to {@code /icon} for this route. The domain
     * owns the lookup identity: host-only routes resolve a single icon per FQDN, while path-based
     * routes use (FQDN, pathPrefix) so siblings under one host don't collide on the icon cache
     * (and the CDN-by-name fallback uses the path segment, not the shared subdomain).
     */
    public String launchpadIconQuery() {
        String q = "host=" + URLEncoder.encode(domainName, StandardCharsets.UTF_8);
        if (pathPrefix != null) {
            q += "&pathPrefix=" + URLEncoder.encode(pathPrefix, StandardCharsets.UTF_8);
        }
        return q;
    }

    /**
     * The URL the launchpad tile links to. A reachable direct-LAN URL wins (see {@link #directUrl});
     * otherwise every route — public or social-gated — links straight to its {@code https://} address.
     * The oauth2-proxy SSO cookie is domain-wide, so an already-signed-in user reaches a social route
     * without a fresh login (Traefik's edge auth passes the request through), and an anonymous user is
     * met at the edge with the Google sign-in carrying the correct return URL. No portal bounce.
     */
    public String launchpadUrl(String callerIp, List<PeerConfiguration> peers,
                               List<VpnClient> vpnClients, String baseDomain) {
        String direct = directUrl(callerIp, peers, vpnClients);
        if (direct != null) return direct;
        return "https://" + domainName + landingPath();
    }

    /**
     * The path segment a launchpad-emitted URL lands on. {@code rootRedirectPath} is the
     * operator's stated landing path and wins when set; otherwise the {@code pathPrefix} is used
     * verbatim (no trailing slash invented — the operator expresses that by typing it). Empty
     * when neither is set: the route catches the whole host and lands on the root.
     */
    private String landingPath() {
        if (rootRedirectPath != null && !rootRedirectPath.isBlank()) return rootRedirectPath;
        return pathPrefix == null ? "" : pathPrefix;
    }

    /**
     * Health visibility, ignoring any per-viewer gating. Used where the viewer is irrelevant
     * (e.g. the published-services admin view, or the "show everything" convenience path).
     *
     * <p>DNS used to be able to hide a tile: a name whose per-service record had not propagated yet
     * would not resolve. Under the operator's single {@code *.<domain>} record every name resolves
     * from the moment the route is written (#331), so the host's reachability is the whole question.
     */
    public LaunchpadVisibility launchpadVisibility(Server.State hostState) {
        // A stream has no URL, so a tile linking to it would link nowhere.
        if (stream) return LaunchpadVisibility.NOT_VISIBLE;
        if (hiddenFromLaunchpad) return LaunchpadVisibility.NOT_VISIBLE;
        // Only a confirmed-unreachable host dims the tile and suppresses its link — UNKNOWN
        // means "we don't have a signal yet"; rendering it as inactive would lie to the
        // operator (and make a healthy service unreachable while the first probe lands).
        if (hostState == Server.State.UNREACHABLE) return LaunchpadVisibility.VISIBLE_INACTIVE;
        return LaunchpadVisibility.VISIBLE_ACTIVE;
    }

    /**
     * Presentation tri-state for the launchpad tile's status dot, derived purely from the host
     * reachability signal. Unlike {@link #launchpadVisibility} — which keeps an un-probed host
     * ACTIVE/clickable — the dot must not go green until reachability is actually confirmed:
     * OK → {@link LaunchpadLiveness#LIVE} (green), UNREACHABLE → {@link LaunchpadLiveness#OFFLINE}
     * (red), UNKNOWN → {@link LaunchpadLiveness#PENDING} (grey, no signal yet — e.g. at startup).
     */
    public LaunchpadLiveness launchpadLiveness(Server.State hostState) {
        return switch (hostState) {
            case OK -> LaunchpadLiveness.LIVE;
            case UNREACHABLE -> LaunchpadLiveness.OFFLINE;
            case UNKNOWN -> LaunchpadLiveness.PENDING;
        };
    }

    /**
     * Launchpad-rendering state for a specific {@code viewer}. The launchpad is a public,
     * viewer-adaptive dashboard: a public route (auth mode {@link AuthMode#NONE}) is shown to
     * everyone; a social-gated route is shown only when the viewer is a known, approved identity
     * that may actually reach it. A route the viewer can't reach is {@link
     * LaunchpadVisibility#NOT_VISIBLE} — no tile is rendered. Hidden-from-launchpad still wins over
     * everything.
     */
    public LaunchpadVisibility launchpadVisibility(Server.State hostState,
                                                   AccessEntry viewer, ForResolvingServiceGroup serviceGroups) {
        if (!isVisibleToLaunchpadViewer(viewer, serviceGroups)) return LaunchpadVisibility.NOT_VISIBLE;
        return launchpadVisibility(hostState);
    }

    /**
     * Whether this route's tile should appear at all for the given launchpad {@code viewer}. A
     * public route (auth mode {@link AuthMode#NONE}) is always visible. A social-gated route is
     * visible only when the viewer is a known, approved identity that {@link
     * AccessEntry#mayAccessService may access} it — admins always, users by group intersection with
     * the host's {@link ForResolvingServiceGroup#allowedGroupsForHost access rule}. An anonymous
     * viewer ({@code null}) or a pending/unknown identity never sees a social-gated tile. The access
     * store is read only for social routes with a candidate viewer — the public path never touches
     * it.
     */
    public boolean isVisibleToLaunchpadViewer(AccessEntry viewer, ForResolvingServiceGroup serviceGroups) {
        if (authMode() == AuthMode.NONE) {
            return true;
        }
        if (viewer == null) {
            return false;
        }
        return viewer.mayAccessService(serviceGroups.allowedGroupsForHost(domainName));
    }

    public State hostState(List<DockerService> localServices, List<VpnClient> vpnClients) {
        // An address inside a peer's allowed IPs is that peer's, and only its tunnel can vouch for it: a
        // Vaier-server container that happens to listen on the same port says nothing about the far end.
        if (vpnClients.stream().anyMatch(p -> p.containsAddress(address))) {
            boolean up = vpnClients.stream().anyMatch(p -> p.containsAddress(address) && p.isConnected());
            return up ? State.OK : State.UNREACHABLE;
        }
        if (localServices.stream().anyMatch(s -> s.isRunning() && s.listensOnPort(port))) return State.OK;
        return State.UNREACHABLE;
    }

    public State hostState(List<DockerService> localServices, List<VpnClient> vpnClients,
                           List<PeerConfiguration> peers) {
        return hostState(localServices, vpnClients, peers, null, null);
    }

    public State hostState(List<DockerService> localServices, List<VpnClient> vpnClients,
                           List<PeerConfiguration> peers, String serverLanCidr) {
        return hostState(localServices, vpnClients, peers, serverLanCidr, null);
    }

    /**
     * Same as {@link #hostState(List, List, List, String)} but also honours per-address
     * LAN reachability (issue #208) — typically the snapshot of the LAN-reachability probe
     * cache. Consulted only for {@code isLanService} routes:
     * <ul>
     *   <li>{@link Reachability#DOWN} → {@link State#UNREACHABLE} regardless of whether the
     *       relay peer's tunnel or the Vaier server's own LAN is up; the route can be
     *       route-reachable while the LAN machine itself is powered off.</li>
     *   <li>{@link Reachability#OK} → fall through to the existing relay/server-CIDR check.</li>
     *   <li>{@link Reachability#UNKNOWN} (address absent from the map, or the map itself null)
     *       → {@link State#UNKNOWN}. Rendering a never-probed host as OK would lie to the
     *       operator with a green icon on a machine we have no signal from.</li>
     * </ul>
     */
    public State hostState(List<DockerService> localServices, List<VpnClient> vpnClients,
                           List<PeerConfiguration> peers, String serverLanCidr,
                           Map<String, Reachability> lanReachabilities) {
        if (isLanService) {
            LanAnchor anchor = LanAnchor.resolve(address, peers, serverLanCidr).orElse(null);
            if (anchor == null) return State.UNREACHABLE;
            Reachability reach = lanReachabilities == null
                ? Reachability.UNKNOWN
                : lanReachabilities.getOrDefault(address, Reachability.UNKNOWN);
            if (reach == Reachability.DOWN) return State.UNREACHABLE;
            // Routability through Vaier still wins over reachability — a known-up LAN host with
            // a dead relay tunnel is unreachable to operators outside the LAN, and reporting
            // UNKNOWN there would hide a real outage.
            boolean routable = anchor.isVaierServer() || anchor.relayPeer()
                .map(relay -> vpnClients.stream().anyMatch(p -> p.containsAddress(relay.ipAddress()) && p.isConnected()))
                .orElse(false);
            if (!routable) return State.UNREACHABLE;
            return reach == Reachability.OK ? State.OK : State.UNKNOWN;
        }
        return hostState(localServices, vpnClients);
    }

    /**
     * The container backing this route, located among the containers discovered on each kind of
     * host. The launchpad uses this to surface the running Docker image + version on a tile.
     *
     * <p>Matching mirrors how a route's {@code address}/{@code port} were assigned at publish
     * time: a LAN-service route resolves against the LAN server at {@code address}; a peer route
     * against the VPN peer whose IP is {@code address}; otherwise the route is backed by a
     * Vaier-server container, matched by container name (the usual persisted address) or, failing
     * that, by port. Port matching is on the container's <em>published</em> (host) port only —
     * the route always stores the host port, and a container's internal port is irrelevant to
     * which service it backs. Matching the internal port would mis-attribute a container to an
     * unrelated service that merely binds the same host port natively (e.g. a service running
     * directly on a machine that is also a registered LAN server). Empty when nothing matches —
     * a LAN service published as a bare host:port, a service running natively (not in Docker),
     * an unreachable host, or a stopped/removed container. A peer route whose peer is present but
     * has no matching container deliberately does <em>not</em> fall back to Vaier-server
     * matching, so an unrelated local container on the same port is never mis-attributed.
     *
     * @param vaierServerContainers        containers on the Vaier server itself
     * @param peerContainersByVpnIp        containers per VPN peer, keyed by the peer's VPN IP
     * @param lanServerContainersByAddress containers per LAN server, keyed by its LAN address
     */
    public Optional<DockerService> backingContainer(
            List<DockerService> vaierServerContainers,
            Map<String, List<DockerService>> peerContainersByVpnIp,
            Map<String, List<DockerService>> lanServerContainersByAddress) {
        if (isLanService) {
            return firstPublishedOnPort(lanServerContainersByAddress.get(address));
        }
        if (peerContainersByVpnIp.containsKey(address)) {
            return firstPublishedOnPort(peerContainersByVpnIp.get(address));
        }
        return vaierServerContainers.stream()
            .filter(c -> address.equals(c.containerName()))
            .findFirst()
            .or(() -> firstPublishedOnPort(vaierServerContainers));
    }

    /**
     * The first container that publishes this route's port on its host. Match is on the
     * <em>published</em> (host) port — never the container's internal port — so a container is
     * only ever attributed to the service actually reachable at that host port.
     */
    private Optional<DockerService> firstPublishedOnPort(List<DockerService> candidates) {
        if (candidates == null) return Optional.empty();
        return candidates.stream()
            .filter(c -> c.ports().stream()
                .anyMatch(m -> m.publicPort() != null && m.publicPort() == port))
            .findFirst();
    }

    /**
     * True when this route has an operator-configured version endpoint — both the endpoint and
     * the property name must be set. The launchpad uses it to surface the running version of a
     * service that is <em>not</em> a discoverable container (typically one running natively on a
     * LAN machine), read over HTTP rather than from the Docker API (issue #210).
     */
    public boolean hasVersionEndpoint() {
        return versionEndpoint != null && !versionEndpoint.isBlank()
            && versionProperty != null && !versionProperty.isBlank();
    }

    /**
     * The absolute URL to GET for this route's version. An operator-supplied endpoint that is
     * already absolute ({@code http(s)://…}) is used verbatim; otherwise it is treated as a path
     * (with or without a leading slash) appended to the service's own {@code protocol://address:port}.
     * Null when no version endpoint is configured.
     */
    public String versionProbeUrl() {
        if (!hasVersionEndpoint()) return null;
        String endpoint = versionEndpoint.trim();
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) return endpoint;
        String scheme = (protocol == null || protocol.isBlank()) ? "http" : protocol;
        String path = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return scheme + "://" + address + ":" + port + path;
    }

    /**
     * Where the backing service actually listens — {@code protocol://address:port}, no path. This
     * is the un-gated way in: everything Vaier itself fetches from a published service (its icon,
     * say) reaches it here rather than through {@code https://<fqdn>}, which for a social-gated
     * route answers 401 to any request that carries no oauth2 cookie — and Vaier carries none.
     */
    public String originUrl() {
        String scheme = (protocol == null || protocol.isBlank()) ? "http" : protocol;
        return scheme + "://" + address + ":" + port;
    }

    /**
     * The {@link #originUrl} of the route serving {@code fqdn} at {@code pathPrefix}, or empty when
     * no route matches. A null and an empty prefix mean the same thing — the host-only route — so
     * callers that normalise a missing prefix to {@code ""} still find it.
     */
    public static Optional<String> originUrlFor(List<ReverseProxyRoute> routes, String fqdn,
                                                String pathPrefix) {
        String wanted = (pathPrefix == null || pathPrefix.isEmpty()) ? null : pathPrefix;
        return routes.stream()
            .filter(r -> fqdn.equals(r.getDomainName()))
            .filter(r -> Objects.equals(wanted, r.getPathPrefix()))
            .findFirst()
            .map(ReverseProxyRoute::originUrl);
    }

    /**
     * This route's running version, read from its configured version endpoint via the
     * {@code prober} driven port. The route owns the interaction end to end: it decides whether
     * there is an endpoint worth probing and builds the URL, then delegates the HTTP call to the
     * port — the application service only passes the port in. Mirrors how {@link #displayName}
     * takes {@link ForResolvingPeerIds}; the service must never call the port itself and feed
     * the result back. Empty when no endpoint is configured or the probe yields nothing.
     */
    public Optional<String> probeVersion(ForProbingServiceVersion prober) {
        if (!hasVersionEndpoint()) return Optional.empty();
        return prober.probeVersion(versionProbeUrl(), versionProperty);
    }

    /** Whether this route leads to a web backend of Vaier's own publishing, whose sign-in Vaier can look at. */
    public boolean hasOwnSignInToDetect() {
        return isVaierManaged() && !stream && !isOauth2EndpointsRouter();
    }

    /**
     * What this route's backend asks for by itself, asked at its {@link #originUrl} — never through Traefik,
     * which would only show Vaier's own sign-in. One same-origin redirect is followed; the rest is the
     * domain's reading of the answer.
     */
    public OwnSignIn detectOwnSignIn(ForProbingServiceSignIn prober, Instant now) {
        String first = originUrl() + ownSignInPath();
        Optional<ServiceProbeAnswer> answer = prober.probe(first);
        Optional<ServiceProbeAnswer> landed = OwnSignIn.followOnce(first, answer).map(prober::probe).orElse(answer);
        return OwnSignIn.classify(landed, now);
    }

    /** Where a visitor lands: the path prefix, else the root redirect, else the root. */
    private String ownSignInPath() {
        String path = pathPrefix != null ? pathPrefix : rootRedirectPath;
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    public String displayName(String baseDomain, List<DockerService> localServices,
                              List<VpnClient> vpnClients, ForResolvingPeerIds peerIdResolver) {
        return displayName(baseDomain, localServices, vpnClients, peerIdResolver, List.of());
    }

    public String displayName(String baseDomain, List<DockerService> localServices,
                              List<VpnClient> vpnClients, ForResolvingPeerIds peerIdResolver,
                              List<PeerConfiguration> peers) {
        String subdomain = extractSubdomain(baseDomain);
        ServerIdentity server = resolveServer(vpnClients, peerIdResolver, peers);
        return stripRedundantPeerSuffix(subdomain, server) + " @ " + server.displayName();
    }

    /**
     * The "short" half of the display name — the operator-facing label without the {@code " @ host"}
     * suffix. Equivalent to the bit before the {@code " @ "} in {@link #displayName}; this method
     * exists so the API can expose the two halves as separate fields and clients never have to
     * reverse the composite by splitting on the delimiter.
     */
    public String shortName(String baseDomain, List<VpnClient> vpnClients,
                            ForResolvingPeerIds peerIdResolver, List<PeerConfiguration> peers) {
        return stripRedundantPeerSuffix(extractSubdomain(baseDomain),
            resolveServer(vpnClients, peerIdResolver, peers));
    }

    /**
     * Where the backing service runs — drives icon choice and grouping in the UI. {@code LAN_SERVICE}
     * is routed through a relay peer; {@code VAIER_SERVER} runs on this Vaier instance itself;
     * {@code PEER_SERVER} runs on a VPN peer. Exposed on the published-services API so the browser
     * doesn't reverse-engineer this from the host display label.
     */
    public ServiceLocation serviceLocation(List<VpnClient> vpnClients,
                                           ForResolvingPeerIds peerIdResolver,
                                           List<PeerConfiguration> peers) {
        if (isLanService) return ServiceLocation.LAN_SERVICE;
        return resolveServer(vpnClients, peerIdResolver, peers).id() == null
            ? ServiceLocation.VAIER_SERVER
            : ServiceLocation.PEER_SERVER;
    }

    public enum ServiceLocation { VAIER_SERVER, PEER_SERVER, LAN_SERVICE }

    /**
     * The Traefik router key for a route on {@code (dnsName, pathPrefix)} — dots become dashes,
     * the path is slugged, and a {@code -router} suffix lands the key in Traefik's router map.
     * Adapters use this both when writing YAML and when looking up an existing route by name.
     * Lives in the domain so the identity rule has a single owner (mirrored by {@link #serviceName}
     * and inverted by {@link #dnsNameFromRouterName}).
     */
    public static String routerName(String dnsName, String pathPrefix) {
        String slug = pathSlug(pathPrefix);
        return dnsName.replace(".", "-") + (slug.isEmpty() ? "" : "-" + slug) + "-router";
    }

    /** The Traefik service key for the same route — same shape, {@code -service} suffix. */
    public static String serviceName(String dnsName, String pathPrefix) {
        String slug = pathSlug(pathPrefix);
        return dnsName.replace(".", "-") + (slug.isEmpty() ? "" : "-" + slug) + "-service";
    }

    /**
     * Recover the host part of a route's DNS name from a router key, e.g.
     * {@code app-example-com-router → app.example.com}. Path slug is not reversed (dashes are
     * ambiguous once joined). Null / non-router input returns null.
     */
    public static String dnsNameFromRouterName(String routerName) {
        if (routerName == null) return null;
        if (!routerName.endsWith("-router")) return null;
        return routerName.substring(0, routerName.length() - "-router".length()).replace("-", ".");
    }

    /**
     * {@code "/auth" → "auth"}, {@code "/builder/ui" → "builder-ui"}, null/blank → {@code ""}.
     * An operator-typed trailing slash is dropped here — the routerName/serviceName identifier
     * is purely structural, the slash never appears in YAML keys.
     */
    private static String pathSlug(String pathPrefix) {
        if (pathPrefix == null || pathPrefix.isBlank()) return "";
        String trimmed = pathPrefix.startsWith("/") ? pathPrefix.substring(1) : pathPrefix;
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.replace('/', '-');
    }

    /**
     * Drops a trailing {@code .<peer>} label the operator put in the subdomain — the
     * {@code " @ <server>"} part already names the peer, so {@code nut.colina27} reads as
     * just {@code nut}. The operator hand-types this suffix, so the match is lenient: the
     * label is compared to the peer id and display name with case and every non-alphanumeric
     * character ignored, so {@code colina27} matches both {@code Colina-27} and {@code Colina 27}.
     */
    private static String stripRedundantPeerSuffix(String subdomain, ServerIdentity server) {
        if (server.id() == null) return subdomain; // Vaier server / unresolved — no peer to strip
        int dot = subdomain.lastIndexOf('.');
        if (dot < 0) return subdomain;
        String lastLabel = canonical(subdomain.substring(dot + 1));
        if (!lastLabel.isEmpty()
                && (lastLabel.equals(canonical(server.id())) || lastLabel.equals(canonical(server.displayName())))) {
            return subdomain.substring(0, dot);
        }
        return subdomain;
    }

    /** Lowercased with every non-alphanumeric character removed, for lenient label matching. */
    private static String canonical(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /**
     * The display name of the machine hosting this route — a VPN peer's editable name, the relay
     * peer's name for a LAN service, or {@link LanAnchor#VAIER_SERVER_NAME}. The launchpad groups
     * and labels tiles by this (issue #209): VPN peer names are the operator-set display labels,
     * the Vaier server name is fixed.
     */
    public String hostDisplayName(List<VpnClient> vpnClients, ForResolvingPeerIds peerIdResolver,
                                  List<PeerConfiguration> peers) {
        return resolveServer(vpnClients, peerIdResolver, peers).displayName();
    }

    /**
     * The display name of the LAN server this route targets, when the route {@link #isLanService}.
     * The card sub-line on the Services page surfaces it ({@code @ NAS}, {@code @ Pool controller})
     * so the operator sees the actual host even though the section heading names the relay peer
     * the traffic flows through. Empty for non-LAN routes (the heading already names the host) and
     * when no registered LAN server matches the route's address.
     */
    public Optional<String> lanServerName(List<LanServer> lanServers) {
        if (!isLanService) return Optional.empty();
        return lanServers.stream()
            .filter(s -> address.equals(s.lanAddress()))
            .findFirst()
            .map(LanServer::name);
    }

    /**
     * The identity of the machine this route's backend runs on: a LAN server bearing its address, a VPN
     * peer whose tunnel address it is, or the Vaier server for anything else (its own hub routes, and a
     * backend on the Docker bridge). Empty when nothing bears the address — a route left pointing at a
     * machine that has since gone belongs to no machine, and saying so is better than guessing.
     *
     * <p>The decision is here rather than in the view assembler because it is the same question
     * "whose service is this?" the publishable feed answers, and answering it two ways is how the same
     * service came to appear under two different machines on two different pages.
     */
    public Optional<MachineId> hostMachineId(List<PeerConfiguration> peers, List<LanServer> lanServers,
                                             MachineId vaierServerId) {
        if (isLanService) {
            return lanServers.stream()
                .filter(s -> address.equals(s.lanAddress()))
                .findFirst()
                .map(LanServer::machineId);
        }
        return peers.stream()
            .filter(p -> address.equals(p.ipAddress()))
            .findFirst()
            .map(PeerConfiguration::machineId)
            .or(() -> Optional.ofNullable(vaierServerId));
    }

    public String directUrl(String callerIp, List<PeerConfiguration> peers, List<VpnClient> vpnClients) {
        if (directUrlDisabled) return null;
        PeerConfiguration peer = hostPeerBesideCaller(callerIp, peers, vpnClients);
        if (peer == null) return null;

        // Path-based routes pass the prefix through to the backend (no StripPrefix middleware
        // on the Traefik side), so the direct LAN bypass URL must include it too — otherwise
        // bare http://backend:port/ hits a different path than the routed one. landingPath()
        // owns the redirect-wins / pathPrefix-verbatim rule so launchpad and direct URLs land
        // on the same place.
        String suffix = landingPath();
        if (isLanService) {
            String scheme = (protocol == null || protocol.isBlank()) ? "http" : protocol;
            return scheme + "://" + address + ":" + port + suffix;
        }
        String lanAddress = peer.lanAddress();
        if (lanAddress == null || lanAddress.isBlank()) return null;
        return "http://" + lanAddress + ":" + port + suffix;
    }

    /**
     * Whether the caller is browsing from the same LAN as the machine hosting this route: the caller's
     * public IP is the tunnel endpoint of the hosting peer (the relay peer, for a LAN service). The fact
     * behind {@link #directUrl}, kept even where no direct URL is handed out, so the launchpad can put
     * the machine the caller is with ahead of the rest.
     */
    public boolean callerOnHostLan(String callerIp, List<PeerConfiguration> peers, List<VpnClient> vpnClients) {
        return hostPeerBesideCaller(callerIp, peers, vpnClients) != null;
    }

    private PeerConfiguration hostPeerBesideCaller(String callerIp, List<PeerConfiguration> peers,
                                                   List<VpnClient> vpnClients) {
        if (callerIp == null || callerIp.isBlank()) return null;
        PeerConfiguration peer = isLanService
            ? findRelayWhoseLanContains(peers, address)
            : peers.stream()
                .filter(p -> p.ipAddress() != null && p.ipAddress().equals(address))
                .findFirst().orElse(null);
        if (peer == null) return null;

        return vpnClients.stream()
            .filter(c -> c.containsAddress(peer.ipAddress()))
            .map(VpnClient::endpointIp)
            .anyMatch(callerIp::equals) ? peer : null;
    }

    private static PeerConfiguration findRelayWhoseLanContains(List<PeerConfiguration> peers, String ip) {
        return LanAnchor.resolve(ip, peers, null).flatMap(LanAnchor::relayPeer).orElse(null);
    }

    /**
     * The {@code subdomain} part of the route's FQDN — everything before {@code baseDomain}.
     * For a domain that doesn't fall under {@code baseDomain}, falls back to the whole domain
     * name. Used by the launchpad sub-line so the browser doesn't split DNS strings to recover
     * the first label.
     */
    public String subdomain(String baseDomain) {
        return extractSubdomain(baseDomain);
    }

    private String extractSubdomain(String baseDomain) {
        if (baseDomain != null && domainName.endsWith("." + baseDomain)) {
            return domainName.substring(0, domainName.length() - baseDomain.length() - 1);
        }
        return domainName;
    }

    /**
     * The peer hosting this route, as an {@code id} (the immutable slug — null for the Vaier
     * server) plus the {@code displayName} shown to operators. Resolving both together keeps
     * {@link #displayName}'s suffix strip (which needs the id) and the labels (which need the
     * display name) consistent — they would drift if derived from two separate lookups.
     */
    private ServerIdentity resolveServer(List<VpnClient> vpnClients, ForResolvingPeerIds peerIdResolver,
                                         List<PeerConfiguration> peers) {
        if (isLanService) {
            PeerConfiguration relay = findRelayWhoseLanContains(peers, address);
            return relay != null
                ? new ServerIdentity(relay.id(), relay.name())
                : ServerIdentity.VAIER_SERVER;
        }
        // Check VPN peers first — a peer IP is unambiguous, whereas port-only Vaier-server
        // matching can produce false positives when a Vaier-server container happens to use the same port.
        boolean isPeer = vpnClients.stream().anyMatch(p -> p.containsAddress(address));
        if (!isPeer) {
            return ServerIdentity.VAIER_SERVER;
        }
        // Prefer the peer's stored id + editable display name; fall back to resolving the id
        // by IP (humanised) when the peers list doesn't carry it.
        return peers.stream()
            .filter(p -> address.equals(p.ipAddress()))
            .findFirst()
            .map(p -> new ServerIdentity(p.id(), p.name()))
            .orElseGet(() -> {
                String resolvedId = peerIdResolver.resolvePeerIdByIp(address);
                return resolvedId.equals(address)
                    ? new ServerIdentity(null, address)
                    : new ServerIdentity(resolvedId, PeerId.display(resolvedId));
            });
    }

    /** A route's host machine: the peer {@code id} (null for the Vaier server) and its display name. */
    private record ServerIdentity(String id, String displayName) {
        static final ServerIdentity VAIER_SERVER = new ServerIdentity(null, LanAnchor.VAIER_SERVER_NAME);
    }

    /**
     * How a route is authenticated, as read back off Traefik. Whether a given middleware name
     * authenticates anyone is {@link AuthMode#isAuthMiddlewareName} — stated once, next to the chain
     * it is membership of.
     */
    @AllArgsConstructor
    @Getter
    @ToString
    public static class AuthInfo {
        private final String type;
        private final String username;
        private final String realm;
    }

    @AllArgsConstructor
    @Getter
    @ToString
    public static class TlsConfig {
        private final String certResolver;
        private final Map<String, Object> additionalConfig;
    }
}
