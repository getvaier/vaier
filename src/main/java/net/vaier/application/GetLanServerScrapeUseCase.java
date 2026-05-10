package net.vaier.application;

import net.vaier.application.DiscoverLanServerContainersUseCase.LanServerContainers;

import java.util.List;

public interface GetLanServerScrapeUseCase {

    /**
     * Latest debounced scrape results for every Docker-enabled LAN server. The {@code status}
     * field is dampened so that a single transient Docker socket failure does not flip the
     * UI's machine-icon colour from green to yellow.
     */
    List<LanServerContainers> getLanServerContainers();

    /**
     * Re-scrape every Docker-enabled LAN server, run the debounce, and publish an SSE event
     * if any host's confirmed status changed.
     */
    void refreshAll();
}
