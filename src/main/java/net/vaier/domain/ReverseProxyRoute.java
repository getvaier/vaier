package net.vaier.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.Server.State;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForProbingServiceVersion;
import net.vaier.domain.port.ForResolvingPeerNames;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Getter
@ToString
public class ReverseProxyRoute {

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;

    private static final Pattern PATH_PREFIX_PATTERN = Pattern.compile("^/[A-Za-z0-9._\\-]+(/[A-Za-z0-9._\\-]+)*$");

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

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig,
                             List<String> middlewares, String rootRedirectPath, boolean directUrlDisabled,
                             boolean isLanService, String protocol, String pathPrefix,
                             boolean hiddenFromLaunchpad, String launchpadAlias,
                             String versionEndpoint, String versionProperty) {
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
    }

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig,
                             List<String> middlewares, String rootRedirectPath, boolean directUrlDisabled,
                             boolean isLanService, String protocol, String pathPrefix,
                             boolean hiddenFromLaunchpad, String launchpadAlias) {
        this(name, domainName, address, port, service, authInfo, entryPoints, tlsConfig, middlewares,
             rootRedirectPath, directUrlDisabled, isLanService, protocol, pathPrefix, hiddenFromLaunchpad,
             launchpadAlias, null, null);
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

    public static void validateDnsName(String dnsName) {
        if (dnsName == null || dnsName.isBlank()) {
            throw new IllegalArgumentException("dnsName must not be blank");
        }
    }

    /**
     * Normalises operator-supplied path prefixes. Null, blank, and "/" all collapse to null (= no
     * PathPrefix, i.e. the route catches everything on its host). A trailing slash is stripped so
     * the Traefik matcher behaves predictably — {@code PathPrefix("/auth")} matches both
     * {@code /auth} and {@code /auth/...}, whereas {@code PathPrefix("/auth/")} would miss bare
     * {@code /auth}.
     */
    public static String normalisePathPrefix(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equals("/")) return null;
        if (trimmed.length() > 1 && trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
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

    // --- domain rules over a list of existing routes ---

    /**
     * True iff any route in {@code existing} sits on the same FQDN — regardless of pathPrefix.
     * The publish flow uses this to decide whether the DNS CNAME already exists and the create can
     * be skipped; the delete flow uses it to decide whether a CNAME can be reclaimed.
     */
    public static boolean hasSiblingOnHost(List<ReverseProxyRoute> existing, String fqdn) {
        return existing.stream().anyMatch(r -> fqdn.equals(r.getDomainName()));
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
            fqdn.equals(r.getDomainName()) && java.util.Objects.equals(pathPrefix, r.getPathPrefix()));
    }

    /**
     * Find the route with the given FQDN + pathPrefix in {@code existing}. Used by delete flows to
     * resolve a user-facing (fqdn, pathPrefix) tuple into a specific routerName.
     */
    public static java.util.Optional<ReverseProxyRoute> findByFqdnAndPath(List<ReverseProxyRoute> existing,
                                                                          String fqdn, String pathPrefix) {
        return existing.stream()
            .filter(r -> fqdn.equals(r.getDomainName()) && java.util.Objects.equals(pathPrefix, r.getPathPrefix()))
            .findFirst();
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
        if (pathPrefix != null) return pathPrefix.substring(pathPrefix.lastIndexOf('/') + 1);
        return domainName.split("\\.")[0];
    }

    /**
     * The query string the launchpad should send to {@code /favicon} for this route. The domain
     * owns the lookup identity: host-only routes resolve a single icon per FQDN, while path-based
     * routes use (FQDN, pathPrefix) so siblings under one host don't collide on the favicon cache
     * (and the CDN-by-name fallback uses the path segment, not the shared subdomain).
     */
    public String launchpadFaviconQuery() {
        String q = "host=" + URLEncoder.encode(domainName, StandardCharsets.UTF_8);
        if (pathPrefix != null) {
            q += "&pathPrefix=" + URLEncoder.encode(pathPrefix, StandardCharsets.UTF_8);
        }
        return q;
    }

    public LaunchpadVisibility launchpadVisibility(DnsState dnsState, Server.State hostState) {
        return launchpadVisibility(dnsState, hostState, true);
    }

    /**
     * Same as {@link #launchpadVisibility(DnsState, Server.State)} but also gates auth-protected
     * routes on whether the launchpad viewer is authenticated. Per V1's auth model — Traefik
     * forward-auth only, all logged-in users are admin — any non-null {@code authInfo} means
     * "internal; log in first." An anonymous viewer therefore must not see those tiles at all
     * (issue #207). Hidden-from-launchpad and DNS-not-propagated still win over auth gating.
     */
    public LaunchpadVisibility launchpadVisibility(DnsState dnsState, Server.State hostState,
                                                   boolean callerAuthenticated) {
        if (hiddenFromLaunchpad) return LaunchpadVisibility.NOT_VISIBLE;
        if (dnsState != DnsState.OK) return LaunchpadVisibility.NOT_VISIBLE;
        if (authInfo != null && !callerAuthenticated) return LaunchpadVisibility.NOT_VISIBLE;
        if (hostState != Server.State.OK) return LaunchpadVisibility.VISIBLE_INACTIVE;
        return LaunchpadVisibility.VISIBLE_ACTIVE;
    }

    public DnsState dnsState(List<DnsRecord> allDnsRecords) {
        boolean found = allDnsRecords.stream()
            .filter(r -> r.name().equals(domainName))
            .anyMatch(r -> r.type() == DnsRecordType.CNAME || r.type() == DnsRecordType.A);
        return found ? DnsState.OK : DnsState.NON_EXISTING;
    }

    public State hostState(List<DockerService> localServices, List<VpnClient> vpnClients) {
        if (localServices.stream().anyMatch(s -> s.isRunning() && s.listensOnPort(port))) return State.OK;
        if (vpnClients.stream().anyMatch(p -> p.containsAddress(address) && p.isConnected())) return State.OK;
        return State.UNREACHABLE;
    }

    public State hostState(List<DockerService> localServices, List<VpnClient> vpnClients,
                           List<PeerConfiguration> peers) {
        return hostState(localServices, vpnClients, peers, null);
    }

    /**
     * Same as {@link #hostState(List, List, List)} but also honours the Vaier server's own LAN
     * CIDR ({@code serverLanCidr}, may be null): a LAN service whose backend falls inside it is
     * reachable directly from the Vaier server, so its host state follows the Vaier server (always
     * OK when we're serving the request) rather than a relay peer's tunnel.
     */
    public State hostState(List<DockerService> localServices, List<VpnClient> vpnClients,
                           List<PeerConfiguration> peers, String serverLanCidr) {
        if (isLanService) {
            LanAnchor anchor = LanAnchor.resolve(address, peers, serverLanCidr).orElse(null);
            if (anchor == null) return State.UNREACHABLE;
            if (anchor.isVaierServer()) return State.OK;
            return anchor.relayPeer()
                .map(relay -> vpnClients.stream().anyMatch(p -> p.containsAddress(relay.ipAddress()) && p.isConnected()))
                .orElse(false) ? State.OK : State.UNREACHABLE;
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
    public java.util.Optional<DockerService> backingContainer(
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
    private java.util.Optional<DockerService> firstPublishedOnPort(List<DockerService> candidates) {
        if (candidates == null) return java.util.Optional.empty();
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
     * This route's running version, read from its configured version endpoint via the
     * {@code prober} driven port. The route owns the interaction end to end: it decides whether
     * there is an endpoint worth probing and builds the URL, then delegates the HTTP call to the
     * port — the application service only passes the port in. Mirrors how {@link #displayName}
     * takes {@link ForResolvingPeerNames}; the service must never call the port itself and feed
     * the result back. Empty when no endpoint is configured or the probe yields nothing.
     */
    public java.util.Optional<String> probeVersion(ForProbingServiceVersion prober) {
        if (!hasVersionEndpoint()) return java.util.Optional.empty();
        return prober.probeVersion(versionProbeUrl(), versionProperty);
    }

    public String displayName(String baseDomain, List<DockerService> localServices,
                              List<VpnClient> vpnClients, ForResolvingPeerNames peerNameResolver) {
        return displayName(baseDomain, localServices, vpnClients, peerNameResolver, List.of());
    }

    public String displayName(String baseDomain, List<DockerService> localServices,
                              List<VpnClient> vpnClients, ForResolvingPeerNames peerNameResolver,
                              List<PeerConfiguration> peers) {
        String subdomain = extractSubdomain(baseDomain);
        String server = resolveServerName(vpnClients, peerNameResolver, peers);
        if (!LanAnchor.VAIER_SERVER_NAME.equals(server) && subdomain.endsWith("." + server)) {
            subdomain = subdomain.substring(0, subdomain.length() - server.length() - 1);
        }
        return subdomain + " @ " + server;
    }

    /**
     * The display name of the machine hosting this route — a VPN peer's editable name, the relay
     * peer's name for a LAN service, or {@link LanAnchor#VAIER_SERVER_NAME}. The launchpad groups
     * and labels tiles by this (issue #209): VPN peer names are the operator-set display labels,
     * the Vaier server name is fixed.
     */
    public String hostDisplayName(List<VpnClient> vpnClients, ForResolvingPeerNames peerNameResolver,
                                  List<PeerConfiguration> peers) {
        return resolveServerName(vpnClients, peerNameResolver, peers);
    }

    public String directUrl(String callerIp, List<PeerConfiguration> peers, List<VpnClient> vpnClients) {
        if (directUrlDisabled) return null;
        if (callerIp == null || callerIp.isBlank()) return null;

        PeerConfiguration peer = isLanService
            ? findRelayWhoseLanContains(peers, address)
            : peers.stream()
                .filter(p -> p.ipAddress() != null && p.ipAddress().equals(address))
                .findFirst().orElse(null);
        if (peer == null) return null;

        String peerEndpointIp = vpnClients.stream()
            .filter(c -> c.containsAddress(peer.ipAddress()))
            .map(VpnClient::endpointIp)
            .filter(ip -> ip != null && !ip.isBlank())
            .findFirst().orElse(null);
        if (peerEndpointIp == null) return null;
        if (!peerEndpointIp.equals(callerIp)) return null;

        // Path-based routes pass the prefix through to the backend (no StripPrefix middleware
        // on the Traefik side), so the direct LAN bypass URL must include it too — otherwise
        // bare http://backend:port/ hits a different path than the routed one.
        String pathPart = (pathPrefix == null) ? "" : pathPrefix;
        String redirectSuffix = (rootRedirectPath == null || rootRedirectPath.isBlank()) ? "" : rootRedirectPath;
        // When both are set, redirect takes precedence as the user's intended landing path.
        String suffix = redirectSuffix.isEmpty() ? (pathPart.isEmpty() ? "" : pathPart + "/") : redirectSuffix;
        if (isLanService) {
            String scheme = (protocol == null || protocol.isBlank()) ? "http" : protocol;
            return scheme + "://" + address + ":" + port + suffix;
        }
        String lanAddress = peer.lanAddress();
        if (lanAddress == null || lanAddress.isBlank()) return null;
        return "http://" + lanAddress + ":" + port + suffix;
    }

    private static PeerConfiguration findRelayWhoseLanContains(List<PeerConfiguration> peers, String ip) {
        return LanAnchor.resolve(ip, peers, null).flatMap(LanAnchor::relayPeer).orElse(null);
    }

    private String extractSubdomain(String baseDomain) {
        if (baseDomain != null && domainName.endsWith("." + baseDomain)) {
            return domainName.substring(0, domainName.length() - baseDomain.length() - 1);
        }
        return domainName;
    }

    private String resolveServerName(List<VpnClient> vpnClients, ForResolvingPeerNames peerNameResolver,
                                     List<PeerConfiguration> peers) {
        if (isLanService) {
            PeerConfiguration relay = findRelayWhoseLanContains(peers, address);
            if (relay != null) return relay.name();
            return LanAnchor.VAIER_SERVER_NAME;
        }
        // Check VPN peers first — a peer IP is unambiguous, whereas port-only Vaier-server
        // matching can produce false positives when a Vaier-server container happens to use the same port.
        boolean isPeer = vpnClients.stream().anyMatch(p -> p.containsAddress(address));
        if (isPeer) {
            // Prefer the peer's editable display name from its configuration; fall back to
            // resolving the id by IP (humanised) when the peers list doesn't carry it.
            return peers.stream()
                .filter(p -> address.equals(p.ipAddress()))
                .map(PeerConfiguration::name)
                .findFirst()
                .orElseGet(() -> {
                    String resolvedId = peerNameResolver.resolvePeerNameByIp(address);
                    return resolvedId.equals(address) ? address : PeerId.display(resolvedId);
                });
        }
        return LanAnchor.VAIER_SERVER_NAME;
    }

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
