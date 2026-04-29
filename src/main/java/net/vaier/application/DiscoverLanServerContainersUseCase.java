package net.vaier.application;

import net.vaier.domain.DockerService;

import java.util.List;

public interface DiscoverLanServerContainersUseCase {

    /**
     * Discover containers on every registered LAN server that has Docker enabled. LAN servers
     * with {@code runsDocker=false} are skipped silently.
     */
    List<LanServerContainers> discoverAllLanServerContainers();

    /**
     * Discover containers on a single named LAN server. Throws
     * {@link IllegalArgumentException} when the server is not registered or when its
     * {@code runsDocker} is false.
     */
    LanServerContainers discoverLanServerContainersForHost(String name);

    record LanServerContainers(
        String name,
        String lanAddress,
        Integer dockerPort,
        String relayPeerName,
        String status,
        List<DockerService> containers
    ) {}
}
