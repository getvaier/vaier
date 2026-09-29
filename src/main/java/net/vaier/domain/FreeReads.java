package net.vaier.domain;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Every published service's <b>free reads</b>: the GET paths Marvin may read without asking, keyed by the
 * service's address (its host, plus its path prefix when it has one). Every list starts empty and grows only
 * by the operator's <b>Always allow</b>, which keeps the call's folder: every path under it is free, with any query.
 * Immutable: each change returns a new set.
 */
public record FreeReads(Map<String, Set<String>> byService) {

    public FreeReads {
        Map<String, Set<String>> copy = new TreeMap<>();
        byService.forEach((address, paths) -> {
            if (!paths.isEmpty()) {
                copy.put(address, Collections.unmodifiableSet(new TreeSet<>(paths)));
            }
        });
        byService = Collections.unmodifiableMap(copy);
    }

    public static FreeReads empty() {
        return new FreeReads(Map.of());
    }

    /** The service's free reads, in path order; none when it has no list. */
    public List<String> of(String host, String pathPrefix) {
        if (host == null || host.isBlank()) {
            return List.of();
        }
        return List.copyOf(byService.getOrDefault(address(host, pathPrefix), Set.of()));
    }

    /** A GET of {@code path}, when the service's list holds it; anything else is sent to call_service. */
    public ServiceCall read(String host, String pathPrefix, String path) {
        ServiceCall call = ServiceCall.proposed("GET", path, null);
        if (of(host, pathPrefix).stream().noneMatch(saved -> covers(saved, call.route()))) {
            throw new IllegalArgumentException("GET " + call.path() + " is not one of this service's free reads, "
                + "so propose it with call_service, method GET: the operator says yes first, and may always "
                + "allow it.");
        }
        return call;
    }

    /** The call's folder is saved. Only a GET may be allowed forever. */
    public FreeReads alwaysAllowing(String host, String pathPrefix, ServiceCall call) {
        if (!call.mayBeAlwaysAllowed()) {
            throw new IllegalArgumentException("Only a GET can be always allowed; a " + call.method()
                + " waits for a yes every time.");
        }
        Map<String, Set<String>> next = new TreeMap<>(byService);
        Set<String> paths = new TreeSet<>(next.getOrDefault(address(host, pathPrefix), Set.of()));
        paths.add(call.allowance());
        next.put(address(host, pathPrefix), paths);
        return new FreeReads(next);
    }

    public FreeReads without(String host, String pathPrefix, String path) {
        String address = address(host, pathPrefix);
        Map<String, Set<String>> next = new TreeMap<>(byService);
        Set<String> paths = new TreeSet<>(next.getOrDefault(address, Set.of()));
        paths.remove(path);
        next.put(address, paths);
        return new FreeReads(next);
    }

    /** Each service on {@code host} that no remaining route publishes any more loses its list. */
    public FreeReads afterUnpublishing(String host, List<ReverseProxyRoute> remainingRoutes) {
        String unpublished = key(host);
        Set<String> stillPublished = new TreeSet<>();
        remainingRoutes.stream()
            .filter(r -> r.getDomainName() != null && !r.getDomainName().isBlank())
            .forEach(r -> stillPublished.add(address(r.getDomainName(), r.getPathPrefix())));
        Map<String, Set<String>> next = new TreeMap<>(byService);
        next.keySet().removeIf(address -> onHost(address, unpublished) && !stillPublished.contains(address));
        return new FreeReads(next);
    }

    /** A saved folder covers everything under it; a saved path covers itself and below; the root only itself. */
    private static boolean covers(String saved, String route) {
        if (saved.equals("/") || route.equals(saved)) {
            return route.equals(saved);
        }
        String folder = saved.endsWith("/") ? saved : saved + "/";
        return route.startsWith(folder) || route.equals(folder.substring(0, folder.length() - 1));
    }

    private static boolean onHost(String address, String host) {
        return address.equals(host) || address.startsWith(host + "/");
    }

    private static String address(String host, String pathPrefix) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Say which published service.");
        }
        String prefix = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        return key(host) + (prefix == null ? "" : prefix);
    }

    private static String key(String host) {
        return host.trim().toLowerCase(Locale.ROOT);
    }
}
