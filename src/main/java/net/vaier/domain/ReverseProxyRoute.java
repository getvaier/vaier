package net.vaier.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.Server.State;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForResolvingPeerNames;

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

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig,
                             List<String> middlewares, String rootRedirectPath, boolean directUrlDisabled,
                             boolean isLanService, String protocol, String pathPrefix,
                             boolean hiddenFromLaunchpad) {
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
    }

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig,
                             List<String> middlewares, String rootRedirectPath, boolean directUrlDisabled,
                             boolean isLanService, String protocol, String pathPrefix) {
        this(name, domainName, address, port, service, authInfo, entryPoints, tlsConfig, middlewares,
             rootRedirectPath, directUrlDisabled, isLanService, protocol, pathPrefix, false);
    }

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig,
                             List<String> middlewares, String rootRedirectPath, boolean directUrlDisabled,
                             boolean isLanService, String protocol) {
        this(name, domainName, address, port, service, authInfo, entryPoints, tlsConfig, middlewares,
             rootRedirectPath, directUrlDisabled, isLanService, protocol, null, false);
    }

    public ReverseProxyRoute(String name, String domainName, String address, int port, String service,
                             AuthInfo authInfo, List<String> entryPoints, TlsConfig tlsConfig,
                             List<String> middlewares, String rootRedirectPath, boolean directUrlDisabled) {
        this(name, domainName, address, port, service, authInfo, entryPoints, tlsConfig, middlewares,
             rootRedirectPath, directUrlDisabled, false, null, null, false);
    }

    public static ReverseProxyRoute lanRoute(String name, String domainName, String host, int port,
                                             String protocol, String service) {
        return new ReverseProxyRoute(name, domainName, host, port, service, null, null, null, null,
            null, false, true, protocol, null, false);
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
    public LaunchpadVisibility launchpadVisibility(DnsState dnsState, Server.State hostState) {
        if (hiddenFromLaunchpad) return LaunchpadVisibility.NOT_VISIBLE;
        if (dnsState != DnsState.OK) return LaunchpadVisibility.NOT_VISIBLE;
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
            if (relay != null && relay.name() != null) return relay.name();
            return LanAnchor.VAIER_SERVER_NAME;
        }
        // Check VPN peers first — a peer IP is unambiguous, whereas port-only Vaier-server
        // matching can produce false positives when a Vaier-server container happens to use the same port.
        boolean isPeer = vpnClients.stream().anyMatch(p -> p.containsAddress(address));
        if (isPeer) {
            String peerName = peerNameResolver.resolvePeerNameByIp(address);
            return peerName.equals(address) ? address : peerName;
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
