package com.wireweave.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import java.util.List;
import java.util.Map;

@AllArgsConstructor
@Getter
@ToString
public class ReverseProxyRoute {
    private final String name;
    private final String domainName;
    private final String address;
    private final int port;
    private final String service;
    private final AuthInfo authInfo;
    private final List<String> entryPoints;
    private final TlsConfig tlsConfig;
    private final List<String> middlewares;

    // Constructor for backward compatibility
    public ReverseProxyRoute(String name, String domainName, String address, int port, String service, AuthInfo authInfo) {
        this(name, domainName, address, port, service, authInfo, null, null, null);
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
