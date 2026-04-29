package net.vaier.domain.port;

public interface ForProbingTcp {

    /**
     * Attempt a TCP connect to {@code host:port} with the given timeout in ms.
     */
    ProbeResult probe(String host, int port, int timeoutMs);

    enum ProbeResult {
        /** TCP handshake completed — port is open. */
        CONNECTED,
        /** Host actively refused the connection — host is alive, port is closed. */
        REFUSED,
        /** Connect timed out or hit a low-level error — host is likely down or filtered. */
        UNREACHABLE
    }
}
