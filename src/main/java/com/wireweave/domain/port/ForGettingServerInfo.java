package com.wireweave.domain.port;

import com.wireweave.domain.Server;
import com.wireweave.domain.DockerService;
import java.util.List;

public interface ForGettingServerInfo {
    List<DockerService> getServicesWithExposedPorts(Server server);
}
