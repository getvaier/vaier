package net.vaier.application;

import net.vaier.domain.LaunchpadVisibility;
import java.util.List;

public interface GetLaunchpadServicesUseCase {

    /**
     * Convenience overload for callers (or tests) that don't need to differentiate
     * anonymous vs. authenticated viewers — assumes authenticated, i.e. shows everything that's
     * otherwise visible. Production traffic goes through the two-arg method via the controller.
     */
    default List<LaunchpadServiceUco> getLaunchpadServices(String callerIp) {
        return getLaunchpadServices(callerIp, true);
    }

    /**
     * Returns the launchpad tiles visible to this caller. {@code callerAuthenticated} gates
     * auth-protected routes (issue #207): when false, any route with forward-auth is filtered
     * out so anonymous viewers don't see internal-only services. The launchpad endpoint itself
     * is anonymously reachable; this flag is set from Authelia's forwarded {@code Remote-User}
     * header.
     */
    List<LaunchpadServiceUco> getLaunchpadServices(String callerIp, boolean callerAuthenticated);

    /**
     * The launchpad's slice of a published service. The domain decides everything the client
     * needs to render:
     * <ul>
     *   <li>{@code displayName} — the label for the tile (operator alias, or last path segment,
     *       or first DNS label). The launchpad never re-derives this from {@code dnsAddress} /
     *       {@code pathPrefix}.</li>
     *   <li>{@code visibility} — tri-state outcome (NOT_VISIBLE entries are filtered out by the
     *       use case and never appear in this list).</li>
     *   <li>{@code faviconQuery} — pre-built query string the client appends to {@code /favicon}.
     *       Path-based siblings get distinct queries so they don't collide on the favicon cache.</li>
     *   <li>{@code image} / {@code version} — the Docker image reference and resolved version of
     *       the container backing this service (issue #210). Both are null when no container
     *       backs the route — e.g. a service published as a bare LAN host:port.</li>
     * </ul>
     * {@code dnsAddress} and {@code pathPrefix} are still here because the client uses them to
     * derive the peer / subdomain sub-line and the browser-tab target.
     */
    record LaunchpadServiceUco(String dnsAddress, String pathPrefix, String hostAddress,
                               LaunchpadVisibility visibility, String url, String displayName,
                               String faviconQuery, String image, String version) {

        /**
         * Convenience constructor for a tile with no backing container — a service published as
         * a bare LAN host:port has no Docker image, so {@code image} and {@code version} are null.
         */
        public LaunchpadServiceUco(String dnsAddress, String pathPrefix, String hostAddress,
                                   LaunchpadVisibility visibility, String url, String displayName,
                                   String faviconQuery) {
            this(dnsAddress, pathPrefix, hostAddress, visibility, url, displayName, faviconQuery,
                null, null);
        }
    }
}
