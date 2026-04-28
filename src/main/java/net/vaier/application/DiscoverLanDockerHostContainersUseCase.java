package net.vaier.application;

import net.vaier.domain.DockerService;

import java.util.List;

public interface DiscoverLanDockerHostContainersUseCase {

    List<LanDockerHostContainers> discoverAllLanDockerHostContainers();

    record LanDockerHostContainers(
        String hostName,
        String hostIp,
        int port,
        String relayPeerName,
        String status,
        List<DockerService> containers
    ) {}
}
