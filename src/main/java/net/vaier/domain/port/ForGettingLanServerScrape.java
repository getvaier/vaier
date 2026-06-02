package net.vaier.domain.port;

import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;

import java.util.List;

/**
 * Driven query port exposing the debounced LAN-server scrape results. Mirror of the inbound
 * {@code GetLanServerScrapeUseCase}'s read side; used by the publishing service to read the
 * cached scrape without coupling to the inbound use case.
 */
public interface ForGettingLanServerScrape {

    /**
     * Latest debounced scrape results for every Docker-enabled LAN server. The {@code status}
     * field is dampened so that a single transient Docker socket failure does not flip the
     * UI's machine-icon colour from green to yellow.
     */
    List<LanServerContainers> getLanServerContainers();
}
