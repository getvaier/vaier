package net.vaier.application.service;

import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.config.ServiceNames;
import net.vaier.application.PublishPeerServiceUseCase.PublishableSource;
import net.vaier.domain.DockerService;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForGettingServerInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetLocalDockerServicesServiceTest {

    @Mock
    ForGettingServerInfo forGettingServerInfo;

    @InjectMocks
    GetLocalDockerServicesService service;

    @Test
    void getUnpublishedLocalServices_excludesWireguardContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class)))
            .thenReturn(List.of(dockerService(ServiceNames.WIREGUARD, 51820, "tcp")));

        assertThat(service.getUnpublishedLocalServices(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedLocalServices_excludesAutheliaContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(dockerService(ServiceNames.AUTHELIA, 9091, "tcp")));

        assertThat(service.getUnpublishedLocalServices(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedLocalServices_excludesRedisContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(dockerService(ServiceNames.REDIS, 6379, "tcp")));

        assertThat(service.getUnpublishedLocalServices(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedLocalServices_excludesVaierContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(dockerService(ServiceNames.VAIER, 8080, "tcp")));

        assertThat(service.getUnpublishedLocalServices(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedLocalServices_excludesWireguardMasqueradeContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(dockerService(ServiceNames.WIREGUARD_MASQUERADE, 8080, "tcp")));

        assertThat(service.getUnpublishedLocalServices(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedLocalServices_traefikOnPort8080_includedWithDashboardRedirect() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(dockerService(ServiceNames.TRAEFIK, 8080, "tcp")));

        List<PublishableService> result = service.getUnpublishedLocalServices(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).containerName()).isEqualTo(ServiceNames.TRAEFIK);
        assertThat(result.get(0).port()).isEqualTo(8080);
        assertThat(result.get(0).rootRedirectPath()).isEqualTo("/dashboard/");
        assertThat(result.get(0).source()).isEqualTo(PublishableSource.LOCAL);
    }

    @Test
    void getUnpublishedLocalServices_traefikOnPort80_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(dockerService("traefik", 80, "tcp")));

        assertThat(service.getUnpublishedLocalServices(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedLocalServices_unknownContainerTcpPort_includedWithNullRedirectPath() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(dockerService("my-app", 3000, "tcp")));

        List<PublishableService> result = service.getUnpublishedLocalServices(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).containerName()).isEqualTo("my-app");
        assertThat(result.get(0).port()).isEqualTo(3000);
        assertThat(result.get(0).rootRedirectPath()).isNull();
    }

    @Test
    void getUnpublishedLocalServices_udpPort_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(dockerService("my-app", 3000, "udp")));

        assertThat(service.getUnpublishedLocalServices(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedLocalServices_alreadyPublishedRoute_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(dockerService("my-app", 3000, "tcp")));
        List<ReverseProxyRoute> existingRoutes = List.of(
            route("my-app", 3000)
        );

        assertThat(service.getUnpublishedLocalServices(existingRoutes)).isEmpty();
    }

    @Test
    void getUnpublishedLocalServices_dockerThrows_returnsEmptyList() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenThrow(new RuntimeException("Docker socket unavailable"));

        assertThat(service.getUnpublishedLocalServices(List.of())).isEmpty();
    }

    private DockerService dockerService(String name, int port, String type) {
        return new DockerService("id", name, "image:latest",
            List.of(new PortMapping(port, port, type, "0.0.0.0")));
    }

    private ReverseProxyRoute route(String address, int port) {
        return new ReverseProxyRoute("route", "app.example.com", address, port, "svc", null);
    }
}
