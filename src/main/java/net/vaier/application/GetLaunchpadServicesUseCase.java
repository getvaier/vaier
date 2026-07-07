package net.vaier.application;

import net.vaier.domain.AccessEntry;
import net.vaier.domain.LaunchpadLiveness;
import net.vaier.domain.LaunchpadVisibility;
import net.vaier.domain.Role;
import java.util.List;

public interface GetLaunchpadServicesUseCase {

    /**
     * Convenience overload for callers (or tests) that don't need to differentiate viewers —
     * behaves as an admin viewer, i.e. shows everything that's otherwise visible. Production
     * traffic goes through the two-arg method via the controller.
     */
    default List<LaunchpadServiceUco> getLaunchpadServices(String callerIp) {
        return getLaunchpadServices(callerIp,
            AccessEntry.builder().role(Role.ADMIN).groups(List.of()).build());
    }

    /**
     * Returns the launchpad tiles this {@code viewer} may actually see. The launchpad is a public,
     * viewer-adaptive dashboard: a {@code null} viewer (anonymous) sees only public (auth mode
     * {@code NONE}) services; a known, approved identity additionally sees every social service it
     * may reach (per the per-service access rule), and never sees ones it can't. A pending or
     * unknown identity gets no extra access — public services only. This is a read: it must not
     * create or mutate any access entry.
     */
    List<LaunchpadServiceUco> getLaunchpadServices(String callerIp, AccessEntry viewer);

    /**
     * The launchpad's slice of a published service. The domain decides everything the client
     * needs to render:
     * <ul>
     *   <li>{@code displayName} — the label for the tile (operator alias, or last path segment,
     *       or first DNS label). The launchpad never re-derives this from {@code dnsAddress} /
     *       {@code pathPrefix}.</li>
     *   <li>{@code visibility} — tri-state outcome (NOT_VISIBLE entries are filtered out by the
     *       use case and never appear in this list). Governs the tile's link and dim behaviour.</li>
     *   <li>{@code liveness} — presentation tri-state for the tile's status dot, derived from the
     *       host reachability signal alone: LIVE (green, confirmed reachable), PENDING (grey, not
     *       yet probed — e.g. at startup), OFFLINE (red, confirmed unreachable). Distinct from
     *       {@code visibility}, which keeps an un-probed host clickable.</li>
     *   <li>{@code iconQuery} — pre-built query string the client appends to {@code /icon}.
     *       Path-based siblings get distinct queries so they don't collide on the icon cache.</li>
     *   <li>{@code peerName} — the display name of the machine hosting the service: a VPN peer's
     *       editable name, the relay peer's name for a LAN service, or "Vaier server". The
     *       launchpad groups and labels tiles by this; it never re-derives the host from DNS.</li>
     *   <li>{@code image} / {@code version} — the Docker image reference and resolved version of
     *       the container backing this service (issue #210). Both are null when no container
     *       backs the route — e.g. a service published as a bare LAN host:port.</li>
     * </ul>
     * {@code dnsAddress} and {@code pathPrefix} are still here because the client uses them to
     * derive the subdomain sub-line and the browser-tab target.
     */
    record LaunchpadServiceUco(String dnsAddress, String pathPrefix, String hostAddress,
                               LaunchpadVisibility visibility, LaunchpadLiveness liveness, String url,
                               String displayName, String subdomain, String iconQuery, String peerName,
                               String image, String version) {

        /**
         * Convenience constructor for a tile with no backing container — a service published as
         * a bare LAN host:port has no Docker image, so {@code image} and {@code version} are null.
         */
        public LaunchpadServiceUco(String dnsAddress, String pathPrefix, String hostAddress,
                                   LaunchpadVisibility visibility, LaunchpadLiveness liveness, String url,
                                   String displayName, String subdomain, String iconQuery, String peerName) {
            this(dnsAddress, pathPrefix, hostAddress, visibility, liveness, url, displayName, subdomain,
                iconQuery, peerName, null, null);
        }
    }
}
