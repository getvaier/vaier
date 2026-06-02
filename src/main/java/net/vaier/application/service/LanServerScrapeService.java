package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetLanServerScrapeUseCase;
import net.vaier.domain.port.ForDiscoveringLanServerContainers;
import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.vaier.domain.port.ForGettingLanServerScrape;
import net.vaier.domain.port.ForPublishingEvents;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class LanServerScrapeService implements GetLanServerScrapeUseCase, ForGettingLanServerScrape {

    // Same dampening shape as LanServerReachabilityService — the Docker socket on a relayed
    // LAN host is just as flap-prone as a TCP probe (slow response, brief socket restart,
    // packet loss). Three consecutive scrapes (~90s at the 30s scheduler cadence) must agree
    // before the cached status flips. Without this the icon strobes green/yellow whenever the
    // remote socket coughs.
    private static final int REQUIRED_CONSECUTIVE_SCRAPES = 3;
    private static final String SSE_TOPIC = "vpn-peers";
    private static final String SSE_EVENT = "lan-servers-updated";

    private final ForDiscoveringLanServerContainers discoverer;
    private final ForPublishingEvents forPublishingEvents;
    private final Map<String, LanServerContainers> cache = new ConcurrentHashMap<>();
    private final Map<String, String> pendingStatus = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingCount = new ConcurrentHashMap<>();

    public LanServerScrapeService(ForDiscoveringLanServerContainers discoverer,
                                  ForPublishingEvents forPublishingEvents) {
        this.discoverer = discoverer;
        this.forPublishingEvents = forPublishingEvents;
    }

    @Override
    public List<LanServerContainers> getLanServerContainers() {
        return List.copyOf(cache.values());
    }

    @Override
    public synchronized void refreshAll() {
        Map<String, String> previousStatuses = statusSnapshot();
        Set<String> seen = new HashSet<>();
        for (LanServerContainers fresh : discoverer.discoverAllLanServerContainers()) {
            String name = fresh.name();
            seen.add(name);
            LanServerContainers existing = cache.get(name);
            if (existing == null) {
                // First observation: commit immediately so the page isn't blank for the 90s
                // window the debounce would otherwise impose. The debounce only kicks in when
                // the host has a confirmed prior state to flip away from.
                cache.put(name, fresh);
                pendingStatus.remove(name);
                pendingCount.remove(name);
                continue;
            }
            if (existing.status().equals(fresh.status())) {
                // Same status: refresh the entry (containers list may have changed) and clear
                // any in-flight pending flip for this host.
                cache.put(name, fresh);
                pendingStatus.remove(name);
                pendingCount.remove(name);
                continue;
            }
            String candidate = pendingStatus.get(name);
            int count = (fresh.status().equals(candidate) ? pendingCount.getOrDefault(name, 0) : 0) + 1;
            if (count >= REQUIRED_CONSECUTIVE_SCRAPES) {
                cache.put(name, fresh);
                pendingStatus.remove(name);
                pendingCount.remove(name);
            } else {
                pendingStatus.put(name, fresh.status());
                pendingCount.put(name, count);
            }
        }
        cache.keySet().retainAll(seen);
        pendingStatus.keySet().retainAll(seen);
        pendingCount.keySet().retainAll(seen);

        if (!previousStatuses.equals(statusSnapshot())) {
            forPublishingEvents.publish(SSE_TOPIC, SSE_EVENT, "");
        }
    }

    private Map<String, String> statusSnapshot() {
        Map<String, String> snap = new HashMap<>();
        cache.forEach((name, c) -> snap.put(name, c.status()));
        return snap;
    }
}
