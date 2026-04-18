package net.vaier.domain.port;

import net.vaier.domain.Server;
import net.vaier.domain.DockerService;
import java.util.List;
import java.util.Optional;

public interface ForGettingServerInfo {
    List<DockerService> getServicesWithExposedPorts(Server server);

    /**
     * Resolve an ephemeral container IP on the vaier Docker network to its container name,
     * so persisted backends use stable DNS names instead of IPs that drift on restart.
     */
    default Optional<String> findContainerNameByIp(Server server, String ipAddress) {
        return Optional.empty();
    }
}
