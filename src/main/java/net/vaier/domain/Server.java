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

    /**
     * Host reachability outcome for a published service. {@link #OK} = host is reachable and the
     * service is up; {@link #UNREACHABLE} = host is confirmed offline (handshake stale, relay
     * tunnel down, or LAN reachability probe came back DOWN); {@link #UNKNOWN} = we don't yet
     * have a signal — typically the LAN-reachability probe hasn't landed in the cache. UNKNOWN
     * must not be conflated with OK (a green icon on a host we haven't probed is misleading).
     */
    public enum State {
        OK, UNREACHABLE, UNKNOWN
    }

    /**
     * Docker daemon URL for the host Vaier itself runs on: the {@code DOCKER_HOST}
     * environment variable when set, otherwise an OS-aware default — a named pipe on
     * Windows, the Unix socket everywhere else.
     */
    public static String localDockerHostUrl() {
        return localDockerHostUrl(System.getenv("DOCKER_HOST"), System.getProperty("os.name"));
    }

    static String localDockerHostUrl(String dockerHostEnv, String osName) {
        if (dockerHostEnv != null && !dockerHostEnv.isBlank()) {
            return dockerHostEnv;
        }
        String os = osName == null ? "" : osName.toLowerCase();
        return os.contains("win")
            ? "npipe:////./pipe/docker_engine"
            : "unix:///var/run/docker.sock";
    }

    public static Server vaierServer() {
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
