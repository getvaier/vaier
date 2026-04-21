package net.vaier.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
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
