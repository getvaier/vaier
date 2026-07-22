package net.vaier.domain.port;

import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;

import java.util.Set;

/**
 * Driven port for the write side of the debounced LAN-server scrape cache. The confirmed scrape
 * results used to live as a {@code ConcurrentHashMap} on {@code LanServerScrapeService}, but a
 * {@code *Service} must not implement a driven ({@code For*}) port — the cache is infrastructure, so
 * it moved to a store adapter. The scrape orchestrator writes confirmed entries here; the debounce
 * bookkeeping stays in the service (application-layer orchestration, mirroring
 * {@code LanServerReachabilityService}). The read side is {@link ForGettingLanServerScrape}.
 */
public interface ForRecordingLanServerScrape {

    /** The confirmed entry for {@code name}, or {@code null} when none has been committed yet. */
    LanServerContainers get(String name);

    /** Commit {@code containers} as the confirmed scrape for {@code name}. */
    void put(String name, LanServerContainers containers);

    /** Drop every confirmed entry whose name is not in {@code names} (a LAN server was removed). */
    void retainOnly(Set<String> names);
}
