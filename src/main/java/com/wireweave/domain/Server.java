package com.wireweave.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@ToString
public class Server {

    @Getter
    private final String address;

    @Getter
    @ToString.Exclude
    private final String apiToken;

    @Getter
    private final Integer port;

    @Getter
    private final boolean tlsEnabled;

    // Legacy constructor for backward compatibility with Portainer API
    public Server(String address, String apiToken) {
        this(address, apiToken, 2375, false);
    }

    public String dockerHostUrl() {
        String protocol = tlsEnabled ? "https" : "tcp";
        int defaultPort = tlsEnabled ? 2376 : 2375;
        int actualPort = port != null ? port : defaultPort;
        return protocol + "://" + address + ":" + actualPort;
    }

    // Deprecated: Use dockerHostUrl() instead
    @Deprecated
    public String endpointsUrl() {
        return "http://" + address + ":9000/api/endpoints";
    }
}
