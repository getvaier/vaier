package net.vaier.domain.port;

/**
 * Runs commands inside a Docker container and restarts containers. The driven side owns the
 * Docker client and the stdout/stderr plumbing — callers depend only on this port, so the
 * exec mechanism lives in one place instead of being copy-pasted into each consumer.
 *
 * <p>The port signature is domain-pure: the adapter catches any underlying checked exceptions
 * (e.g. {@code IOException}, {@code InterruptedException}) and rethrows them as an unchecked
 * {@link RuntimeException}, so {@code java.io.*} never leaks across this boundary.
 */
public interface ForExecutingInContainer {

    /** Runs {@code command} in {@code containerName} and returns its captured stdout. */
    String execute(String containerName, String... command);

    /** Runs {@code command} in {@code containerName} with {@code input} piped to its stdin. */
    String executeWithInput(String containerName, String input, String... command);

    /**
     * Restarts {@code containerName}, then — if it exists — its {@code <containerName>-masquerade}
     * sidecar. A missing or unrestartable sidecar is logged and ignored; failure to restart the
     * primary container throws a {@link RuntimeException}.
     */
    void restartWithMasqueradeSidecar(String containerName);
}
