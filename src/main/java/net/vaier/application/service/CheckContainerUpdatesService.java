package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.CheckContainerUpdatesUseCase;
import net.vaier.application.DiscoverLocalContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase.PeerContainers;
import net.vaier.domain.ContainerUpdateStatus;
import net.vaier.domain.DockerService;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForCheckingRegistryDigests;
import net.vaier.domain.port.ForGettingImageDigests;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckContainerUpdatesService implements CheckContainerUpdatesUseCase {

    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final DiscoverLocalContainersUseCase discoverLocalContainers;
    private final DiscoverPeerContainersUseCase discoverPeerContainers;
    private final ForCheckingRegistryDigests registryDigests;
    private final ForGettingImageDigests imageDigests;

    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    @Override
    public List<ContainerUpdateStatus> checkAll() {
        List<ContainerWithSource> allContainers;
        try {
            allContainers = collectAllContainers();
        } catch (Exception e) {
            log.warn("Failed to discover containers for update check: {}", e.getMessage());
            return getCachedResults();
        }

        Map<String, ContainerWithSource> uniqueByImageTag = new LinkedHashMap<>();
        for (ContainerWithSource c : allContainers) {
            String[] parsed = parseImageAndTag(c.service.image());
            String key = parsed[0] + ":" + parsed[1];
            uniqueByImageTag.putIfAbsent(key, c);
        }

        List<ContainerUpdateStatus> results = new ArrayList<>();
        for (var entry : uniqueByImageTag.entrySet()) {
            String key = entry.getKey();
            ContainerWithSource c = entry.getValue();
            DockerService svc = c.service;
            String[] parsed = parseImageAndTag(svc.image());
            String image = parsed[0];
            String tag = parsed[1];

            // When the image tag is "latest" but we have a specific version from labels,
            // use the resolved version for a more precise registry check
            String resolvedVersion = svc.version();
            boolean hasSpecificVersion = resolvedVersion != null
                    && !resolvedVersion.isBlank()
                    && !"latest".equals(resolvedVersion)
                    && !resolvedVersion.equals(tag);
            String registryTag = hasSpecificVersion ? resolvedVersion : tag;
            boolean latestTag = "latest".equals(registryTag);

            Optional<String> localDigest = imageDigests.getImageDigest(c.server, svc.image());
            if (localDigest.isEmpty()) {
                log.debug("No local digest for {}:{}, skipping", image, registryTag);
                continue;
            }

            Optional<String> remoteDigest = registryDigests.getRemoteDigest(image, registryTag);
            if (remoteDigest.isEmpty()) {
                log.debug("No remote digest for {}:{}, skipping", image, registryTag);
                continue;
            }

            boolean updateAvailable = !localDigest.get().equals(remoteDigest.get());
            Instant now = Instant.now();

            ContainerUpdateStatus status = new ContainerUpdateStatus(
                    image, registryTag, localDigest.get(), remoteDigest.get(),
                    updateAvailable, latestTag, now);
            results.add(status);
            cache.put(key, new CachedEntry(status, now));
        }

        return results;
    }

    @Override
    public List<ContainerUpdateStatus> getCachedResults() {
        Instant cutoff = Instant.now().minus(CACHE_TTL);
        List<ContainerUpdateStatus> results = new ArrayList<>();
        cache.forEach((key, entry) -> {
            if (entry.cachedAt.isAfter(cutoff)) {
                results.add(entry.status);
            }
        });
        cache.entrySet().removeIf(e -> e.getValue().cachedAt.isBefore(cutoff));
        return results;
    }

    private List<ContainerWithSource> collectAllContainers() {
        List<ContainerWithSource> all = new ArrayList<>();

        List<DockerService> local = discoverLocalContainers.discover();
        Server localServer = Server.local();
        for (DockerService svc : local) {
            all.add(new ContainerWithSource(svc, localServer));
        }

        List<PeerContainers> peers = discoverPeerContainers.discoverAll();
        for (PeerContainers peer : peers) {
            if (!"OK".equals(peer.status())) continue;
            Server peerServer = new Server(peer.vpnIp(), 2375, false);
            for (DockerService svc : peer.containers()) {
                all.add(new ContainerWithSource(svc, peerServer));
            }
        }

        return all;
    }

    static String[] parseImageAndTag(String imageRef) {
        int colonIdx = imageRef.lastIndexOf(':');
        int slashIdx = imageRef.lastIndexOf('/');
        if (colonIdx > slashIdx && colonIdx > 0) {
            return new String[]{imageRef.substring(0, colonIdx), imageRef.substring(colonIdx + 1)};
        }
        return new String[]{imageRef, "latest"};
    }

    private record ContainerWithSource(DockerService service, Server server) {}

    private record CachedEntry(ContainerUpdateStatus status, Instant cachedAt) {}
}
