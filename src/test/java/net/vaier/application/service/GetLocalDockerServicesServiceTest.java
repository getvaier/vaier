package net.vaier.application.service;

import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.config.ServiceNames;
import net.vaier.application.PublishPeerServiceUseCase.PublishableSource;
import net.vaier.domain.DockerService;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForGettingServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetLocalDockerServicesServiceTest {

    private static final String VAIER_NETWORK = "vaier-network";
    private static final String GATEWAY_IP = "172.20.0.1";

    @Mock
    ForGettingServerInfo forGettingServerInfo;

    GetLocalDockerServicesService service;

    @BeforeEach
    void setUp() {
        service = new GetLocalDockerServicesService(forGettingServerInfo, VAIER_NETWORK, GATEWAY_IP);
    }

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

    @Test
    void getUnpublishedLocalServices_containerOnVaierNetwork_usesContainerNameAndPrivatePort() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(new DockerService("id", "my-app", "image:latest", "latest",
                List.of(new PortMapping(3001, null, "tcp", "0.0.0.0")),
                List.of(VAIER_NETWORK))));

        List<PublishableService> result = service.getUnpublishedLocalServices(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).address()).isEqualTo("my-app");
        assertThat(result.get(0).port()).isEqualTo(3001);
    }

    @Test
    void getUnpublishedLocalServices_containerOnOtherNetworkWithPublicPort_usesGatewayAndPublicPort() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(new DockerService("id", "uptime-kuma", "image:latest", "latest",
                List.of(new PortMapping(3001, 3001, "tcp", "0.0.0.0")),
                List.of("uptime-kuma_default"))));

        List<PublishableService> result = service.getUnpublishedLocalServices(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).address()).isEqualTo(GATEWAY_IP);
        assertThat(result.get(0).port()).isEqualTo(3001);
        assertThat(result.get(0).containerName()).isEqualTo("uptime-kuma");
    }

    @Test
    void getUnpublishedLocalServices_containerOnOtherNetworkWithoutPublicPort_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(new DockerService("id", "my-app", "image:latest", "latest",
                List.of(new PortMapping(3001, null, "tcp", "0.0.0.0")),
                List.of("some-other-network"))));

        assertThat(service.getUnpublishedLocalServices(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedLocalServices_crossNetworkContainerAlreadyPublished_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(new DockerService("id", "uptime-kuma", "image:latest", "latest",
                List.of(new PortMapping(3001, 3001, "tcp", "0.0.0.0")),
                List.of("uptime-kuma_default"))));
        List<ReverseProxyRoute> existingRoutes = List.of(route(GATEWAY_IP, 3001));

        assertThat(service.getUnpublishedLocalServices(existingRoutes)).isEmpty();
    }

    private DockerService dockerService(String name, int port, String type) {
        return new DockerService("id", name, "image:latest", "latest",
            List.of(new PortMapping(port, null, type, "0.0.0.0")),
            List.of(VAIER_NETWORK));
    }

    private ReverseProxyRoute route(String address, int port) {
        return new ReverseProxyRoute("route", "app.example.com", address, port, "svc", null);
    }
}
