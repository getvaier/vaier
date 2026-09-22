package net.vaier.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The catalogue of which containers running on the Vaier server may be offered as publishable
 * services. The containers of Vaier's own stack are hidden; the operator's own containers on the
 * same host are offered on every TCP port they expose. A few of Vaier's own are a deliberate
 * carve-out — offered, but only on the ports that are worth publishing, and with a default
 * root-redirect path.
 *
 * <p>The names below are the {@code container_name}s in Vaier's {@code docker-compose.yml}, spelled
 * as literals rather than borrowed from {@code ServiceNames}: a container name and a subdomain that
 * happen to read the same are different concepts, and sharing a constant between them couples this
 * to the auth chain's naming. {@code VaierServerCatalogueTest} reads the compose file and fails the
 * build if a service there is neither hidden nor offered — the list cannot go stale unnoticed again,
 * which is exactly how oauth2-proxy, Dex, CrowdSec, the socket proxy and the offline page ended up
 * quietly on offer while decommissioned Authelia and Redis kept their place.
 */
public final class VaierServerCatalogue {

    private VaierServerCatalogue() {}

    /** Containers of Vaier's own stack that are never offered as publishable services. */
    private static final Set<String> EXCLUDED = Set.of(
        // Vaier, the tunnel, and the tunnel's masquerade sidecar.
        "vaier", "wireguard", "wireguard-masquerade",
        // The social-login chain — already reachable at oauth2.<domain> and dex.<domain>.
        "oauth2-proxy", "dex",
        // The edge's threat detection (its bouncer is a Traefik plugin, not a container).
        "crowdsec",
        // The Docker socket proxy. It serves the Docker API on 2375, so publishing it would put root
        // on every container behind a public hostname — the sharpest reason this list exists.
        "docker-proxy",
        // The "Vaier is down" placeholder, and the access-log rotator.
        "vaier-offline", "traefik-logrotate",
        // The sidecars that add the fleet's LAN routes to the host and to Traefik.
        "host-lan-routes", "traefik-lan-routes",
        // The one-shot init containers that render config before their service starts.
        "vaier-init", "oauth2-proxy-init", "dex-init", "geoip-init",
        // Decommissioned in #305 and gone from the stack — kept because an updated host can still be
        // carrying the containers, and a leftover session store is not something to offer publishing.
        "authelia", "redis"
    );

    private record OfferedService(Set<Integer> allowedPorts, String rootRedirectPath) {}

    /**
     * The carve-out: containers Vaier knows specifically and offers only on the listed ports. Traefik's
     * dashboard is the one part of Vaier's own stack an operator has a reason to publish — behind auth,
     * on the hostname of their choosing.
     */
    private static final Map<String, OfferedService> OFFERED = Map.of(
        "traefik", new OfferedService(Set.of(8080), "/dashboard/")
    );

    /** Whether {@code containerName} is one of Vaier's own infrastructure containers. */
    public static boolean isExcluded(String containerName) {
        return EXCLUDED.contains(containerName.toLowerCase());
    }

    /**
     * Whether {@code containerName} is a container of Vaier's own stack that Vaier deliberately offers.
     * Says nothing about the operator's containers: those are offered because they are not excluded, not
     * because the catalogue names them.
     */
    public static boolean isOffered(String containerName) {
        return OFFERED.containsKey(containerName.toLowerCase());
    }

    /**
     * Whether a container running <b>on the Vaier server</b> is one of Vaier's own — hidden or offered
     * alike. Offered is not the opposite of Vaier's own: Traefik is offered for publishing and is still
     * part of the stack a Vaier release pins. Says nothing about a container of the same name on a peer
     * or a LAN server, which is the operator's.
     */
    public static boolean isVaierOwnStack(String containerName) {
        return isExcluded(containerName) || isOffered(containerName);
    }

    /**
     * The Vaier-server containers an <b>update sweep</b> may ask registries about: the operator's own, and
     * nothing of Vaier's own stack (#353).
     *
     * <p><b>Why they are dropped rather than swept and hidden.</b> These images are pinned by a Vaier
     * release and move with it, so the only honest resolution of a mark on one is "wait for a Vaier
     * release" — and the mark is not merely a mark: {@code ImageUpdateTracker} is edge-triggered and not
     * baseline-quiet, so a re-pushed pinned tag reaches every admin as mail. An alert nobody can act on
     * teaches an operator to filter the channel, and that costs the alerts that matter. Dropping them
     * <em>before</em> the registries are asked also keeps them from spending a rate limit that is real and
     * shared with every image the operator <em>can</em> act on.
     *
     * <p><b>No exception for {@code vaier} itself.</b> Settings → Update asks for itself rather than
     * reading a verdict from here, so the mark buys nothing — and on a host that builds Vaier locally the
     * local digest differs from what Docker Hub serves for {@code latest}, which makes the mark read
     * "newer available" when acting on it would <b>downgrade</b>. A mark that talks an operator into a
     * downgrade is worse than no mark.
     *
     * <p><b>Only meaningful for the Vaier server.</b> A peer or LAN server running its own {@code traefik}
     * or {@code redis} is the operator's container and is swept exactly as before; that distinction is why
     * the two judging entry points are separate, and it is why this is not applied to their scrapes.
     */
    public static List<DockerService> sweepable(List<DockerService> vaierServerContainers) {
        if (vaierServerContainers == null) {
            return List.of();
        }
        return vaierServerContainers.stream()
            .filter(container -> !isVaierOwnStack(container.containerName()))
            .toList();
    }

    /**
     * Whether {@code port} of {@code containerName} may be published. An offered container is
     * restricted to its listed ports; any other container has all of its ports publishable.
     */
    public static boolean isPublishablePort(String containerName, int port) {
        OfferedService offered = OFFERED.get(containerName.toLowerCase());
        return offered == null || offered.allowedPorts().contains(port);
    }

    /** The default root-redirect path for an offered container, or null when none applies. */
    public static String rootRedirectPath(String containerName) {
        OfferedService offered = OFFERED.get(containerName.toLowerCase());
        return offered == null ? null : offered.rootRedirectPath();
    }
}
