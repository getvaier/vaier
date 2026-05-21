package net.vaier.domain.port;

import java.util.Optional;

/**
 * Reads a service's running version over HTTP, for services that are not discoverable Docker
 * containers — typically something running natively on a LAN machine. The value is taken from a
 * {@code property="value"} pair in the response body (Prometheus text-exposition label style).
 */
public interface ForProbingServiceVersion {

    /**
     * GET {@code url} and return the value of the label {@code property}. Empty when the URL is
     * unreachable, the response is not {@code 200}, or the property is absent — a version probe
     * must never fail the caller.
     */
    Optional<String> probeVersion(String url, String property);
}
