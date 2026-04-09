package net.vaier.application.service;

import net.vaier.domain.DockerService;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForGettingServerInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoverLocalContainersServiceTest {

    @Mock
    ForGettingServerInfo forGettingServerInfo;

    @InjectMocks
    DiscoverLocalContainersService service;

    @Test
    void discover_usesLocalDockerSocket() {
        List<DockerService> expected = List.of(dockerService("my-app", 8080));
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("unix:///var/run/docker.sock"))
        )).thenReturn(expected);

        assertThat(service.discover()).isSameAs(expected);
    }

    @Test
    void discover_returnsEmptyListWhenNoServicesRunning() {
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("unix:///var/run/docker.sock"))
        )).thenReturn(List.of());

        assertThat(service.discover()).isEmpty();
    }

    @Test
    void discover_propagatesExceptionFromAdapter() {
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("unix:///var/run/docker.sock"))
        )).thenThrow(new RuntimeException("socket not found"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> service.discover());
    }

    private DockerService dockerService(String name, int port) {
        return new DockerService("id1", name, "image:latest", "latest",
            List.of(new DockerService.PortMapping(port, port, "tcp", "0.0.0.0")), List.of(), "running");
    }
}
