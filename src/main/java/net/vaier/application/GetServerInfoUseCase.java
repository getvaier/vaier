package net.vaier.application;

import net.vaier.domain.DockerService;
import net.vaier.domain.Server;
import java.util.List;

public interface GetServerInfoUseCase {
    List<DockerService> getServicesWithExposedPorts(Server server);
}
