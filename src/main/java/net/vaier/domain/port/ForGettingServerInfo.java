package net.vaier.domain.port;

import net.vaier.domain.Server;
import net.vaier.domain.DockerService;
import java.util.List;

public interface ForGettingServerInfo {
    List<DockerService> getServicesWithExposedPorts(Server server);
}
