package net.vaier.application;

import net.vaier.domain.DockerService;

import java.util.List;

public interface DiscoverLocalContainersUseCase {

    List<DockerService> discover();
}
