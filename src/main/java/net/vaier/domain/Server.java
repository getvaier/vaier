package net.vaier.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@ToString
public class Server {

    @Getter
    private final String address;

    @Getter
    private final Integer port;

    @Getter
    private final boolean tlsEnabled;

    public String dockerHostUrl() {
        // Support unix socket
        if (address.startsWith("unix://") || address.startsWith("/")) {
            return address.startsWith("unix://") ? address : "unix://" + address;
        }

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

    public enum State {
        OK, UNREACHABLE
    }

    public static Server local() {
        return new Server("/var/run/docker.sock", 0, false);
    }
}
