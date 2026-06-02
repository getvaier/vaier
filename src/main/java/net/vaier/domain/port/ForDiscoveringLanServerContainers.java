package net.vaier.domain.port;

import net.vaier.domain.DockerService;

import java.util.List;

/**
 * Driven query port for the live LAN-server container discovery. Mirror of the inbound
 * {@code DiscoverLanServerContainersUseCase}; used by other domains' services (e.g. the LAN
 * server scrape) that need current discovery results without coupling to the inbound use case.
 */
public interface ForDiscoveringLanServerContainers {

    List<LanServerContainers> discoverAllLanServerContainers();

    record LanServerContainers(
        String name,
        String lanAddress,
        Integer dockerPort,
        String relayPeerName,
        String status,
        List<DockerService> containers
    ) {}
}
