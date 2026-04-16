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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetServerInfoServiceTest {

    @Mock ForGettingServerInfo forGettingServerInfo;
    @InjectMocks GetServerInfoService service;

    @Test
    void getServicesWithExposedPorts_delegatesToPort() {
        Server server = new Server("10.13.13.2", 2375, false);
        DockerService dockerService = mock(DockerService.class);
        when(forGettingServerInfo.getServicesWithExposedPorts(server)).thenReturn(List.of(dockerService));

        assertThat(service.getServicesWithExposedPorts(server)).containsExactly(dockerService);
    }
}
