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
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null && !dockerHost.isBlank()) {
            if (dockerHost.startsWith("tcp://")) {
                String hostPort = dockerHost.substring("tcp://".length());
                int colon = hostPort.lastIndexOf(':');
                if (colon > 0) {
                    String host = hostPort.substring(0, colon);
                    int port = Integer.parseInt(hostPort.substring(colon + 1));
                    return new Server(host, port, false);
                }
                return new Server(hostPort, 2375, false);
            }
            if (dockerHost.startsWith("unix://")) {
                return new Server(dockerHost.substring("unix://".length()), 0, false);
            }
        }
        return new Server("/var/run/docker.sock", 0, false);
    }
}
