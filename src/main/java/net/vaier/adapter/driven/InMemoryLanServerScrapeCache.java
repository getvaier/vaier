package net.vaier.adapter.driven;

import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.vaier.domain.port.ForGettingLanServerScrape;
import net.vaier.domain.port.ForRecordingLanServerScrape;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory store for the debounced LAN-server scrape results. Owns the map that used to live on
 * {@code LanServerScrapeService} — a {@code *Service} must not implement a driven ({@code For*})
 * port, so the confirmed-results cache moved here. The scrape orchestrator writes confirmed entries
 * via {@link ForRecordingLanServerScrape}; the publishing domain (and the scrape use case itself)
 * read via {@link ForGettingLanServerScrape}.
 *
 * <p>Mirrors {@link InMemoryLanReachabilityCache}: a {@code ConcurrentHashMap} written from the
 * scrape scheduler and read from request threads.
 */
@Component
public class InMemoryLanServerScrapeCache implements ForGettingLanServerScrape, ForRecordingLanServerScrape {

    private final ConcurrentMap<String, LanServerContainers> cache = new ConcurrentHashMap<>();

    @Override
    public List<LanServerContainers> getLanServerContainers() {
        return List.copyOf(cache.values());
    }

    @Override
    public LanServerContainers get(String name) {
        return cache.get(name);
    }

    @Override
    public void put(String name, LanServerContainers containers) {
        cache.put(name, containers);
    }

    @Override
    public void retainOnly(Set<String> names) {
        cache.keySet().retainAll(names);
    }
}
