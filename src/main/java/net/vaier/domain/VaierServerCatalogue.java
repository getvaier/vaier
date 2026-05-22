package net.vaier.domain;

import net.vaier.config.ServiceNames;

import java.util.Map;
import java.util.Set;

/**
 * The catalogue of which containers running on the Vaier server may be offered as publishable
 * services. Vaier's own infrastructure containers are excluded outright; a few known containers
 * are restricted to specific ports and carry a default root-redirect path.
 */
public final class VaierServerCatalogue {

    private VaierServerCatalogue() {}

    /** Infrastructure containers that are never offered as publishable services. */
    private static final Set<String> EXCLUDED = Set.of(
        ServiceNames.WIREGUARD, ServiceNames.WIREGUARD_MASQUERADE,
        ServiceNames.AUTHELIA, ServiceNames.REDIS, ServiceNames.VAIER
    );

    private record KnownService(Set<Integer> allowedPorts, String rootRedirectPath) {}

    /** Containers Vaier knows specifically — only the listed ports of these are publishable. */
    private static final Map<String, KnownService> KNOWN = Map.of(
        ServiceNames.TRAEFIK, new KnownService(Set.of(8080), "/dashboard/")
    );

    /** Whether {@code containerName} is one of Vaier's own infrastructure containers. */
    public static boolean isExcluded(String containerName) {
        return EXCLUDED.contains(containerName.toLowerCase());
    }

    /**
     * Whether {@code port} of {@code containerName} may be published. A known container is
     * restricted to its listed ports; any other container has all of its ports publishable.
     */
    public static boolean isPublishablePort(String containerName, int port) {
        KnownService known = KNOWN.get(containerName.toLowerCase());
        return known == null || known.allowedPorts().contains(port);
    }

    /** The default root-redirect path for a known container, or null when none applies. */
    public static String rootRedirectPath(String containerName) {
        KnownService known = KNOWN.get(containerName.toLowerCase());
        return known == null ? null : known.rootRedirectPath();
    }
}
