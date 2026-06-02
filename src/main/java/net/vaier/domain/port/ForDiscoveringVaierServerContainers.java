package net.vaier.domain.port;

import net.vaier.domain.DockerService;

import java.util.List;

/**
 * Driven query port exposing the cached Vaier-server container scrape. Mirror of the inbound
 * {@code DiscoverVaierServerContainersUseCase}; used by other domains' services (e.g. publishing)
 * that need a read-only view of discovered Vaier-server containers without coupling to the
 * inbound use case.
 */
public interface ForDiscoveringVaierServerContainers {

    List<DockerService> discover();
}
