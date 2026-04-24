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

@AllArgsConstructor
@Getter
@ToString
public class ReverseProxyRoute {

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;

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

    public String displayName(String baseDomain, List<DockerService> localServices,
                              List<VpnClient> vpnClients, ForResolvingPeerNames peerNameResolver) {
        String subdomain = extractSubdomain(baseDomain);
        String server = resolveServerName(vpnClients, peerNameResolver);
        if (!"local".equals(server) && subdomain.endsWith("." + server)) {
            subdomain = subdomain.substring(0, subdomain.length() - server.length() - 1);
        }
        return subdomain + " @ " + server;
    }

    public String directUrl(String callerIp, List<PeerConfiguration> peers, List<VpnClient> vpnClients) {
        if (directUrlDisabled) return null;
        if (callerIp == null || callerIp.isBlank()) return null;
        PeerConfiguration peer = peers.stream()
            .filter(p -> p.ipAddress() != null && p.ipAddress().equals(address))
            .findFirst().orElse(null);
        if (peer == null) return null;
        String lanAddress = peer.lanAddress();
        if (lanAddress == null || lanAddress.isBlank()) return null;

        String peerEndpointIp = vpnClients.stream()
            .filter(c -> c.containsAddress(peer.ipAddress()))
            .map(VpnClient::endpointIp)
            .filter(ip -> ip != null && !ip.isBlank())
            .findFirst().orElse(null);
        if (peerEndpointIp == null) return null;
        if (!peerEndpointIp.equals(callerIp)) return null;

        return "http://" + lanAddress + ":" + port;
    }

    private String extractSubdomain(String baseDomain) {
        if (baseDomain != null && domainName.endsWith("." + baseDomain)) {
            return domainName.substring(0, domainName.length() - baseDomain.length() - 1);
        }
        return domainName;
    }

    private String resolveServerName(List<VpnClient> vpnClients, ForResolvingPeerNames peerNameResolver) {
        // Check VPN peers first — a peer IP is unambiguous, whereas port-only local
        // matching can produce false positives when a local container happens to use the same port.
        boolean isPeer = vpnClients.stream().anyMatch(p -> p.containsAddress(address));
        if (isPeer) {
            String peerName = peerNameResolver.resolvePeerNameByIp(address);
            return peerName.equals(address) ? address : peerName;
        }
        return "local";
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
