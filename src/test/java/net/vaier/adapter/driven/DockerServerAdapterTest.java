package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerHostConfig;
import com.github.dockerjava.api.model.ContainerNetworkSettings;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.transport.DockerHttpClient;
import net.vaier.domain.DockerService;
import net.vaier.domain.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DockerServerAdapterTest {

    @Test
    void getServicesWithExposedPorts_hostNetworkLocalServer_usesRawHttpToGetExposedPorts() {
        // Given: a local server with a host-network container exposing port 19999/tcp
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("abc123");
        when(container.getNames()).thenReturn(new String[]{"/netdata"});
        when(container.getImage()).thenReturn("netdata/netdata:latest");
        when(container.getImageId()).thenReturn("sha256:abc");

        when(container.getState()).thenReturn("running");

        ContainerHostConfig hostConfig = mock(ContainerHostConfig.class);
        when(hostConfig.getNetworkMode()).thenReturn("host");
        when(container.getHostConfig()).thenReturn(hostConfig);

        ContainerNetworkSettings networkSettings = mock(ContainerNetworkSettings.class);
        when(networkSettings.getNetworks()).thenReturn(Map.of("host", mock(com.github.dockerjava.api.model.ContainerNetwork.class)));
        when(container.getNetworkSettings()).thenReturn(networkSettings);

        when(listCmd.exec()).thenReturn(List.of(container));

        // Mock raw HTTP response with ExposedPorts
        String inspectJson = """
            {"Config":{"ExposedPorts":{"19999/tcp":{}}}}
            """;
        DockerHttpClient.Response httpResponse = mock(DockerHttpClient.Response.class);
        when(httpResponse.getBody()).thenReturn(new ByteArrayInputStream(inspectJson.getBytes(StandardCharsets.UTF_8)));
        when(dockerHttpClient.execute(any(DockerHttpClient.Request.class))).thenReturn(httpResponse);

        // Mock image inspect for version
        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:abc")).thenReturn(inspectImageCmd);
        InspectImageResponse imageResponse = mock(InspectImageResponse.class);
        when(inspectImageCmd.exec()).thenReturn(imageResponse);

        // When
        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.local());

        // Then: netdata should appear with its exposed port
        assertThat(services).hasSize(1);
        assertThat(services.get(0).containerName()).isEqualTo("netdata");
        assertThat(services.get(0).ports()).hasSize(1);
        assertThat(services.get(0).ports().get(0).privatePort()).isEqualTo(19999);
        assertThat(services.get(0).ports().get(0).publicPort()).isEqualTo(19999);
        assertThat(services.get(0).ports().get(0).type()).isEqualTo("tcp");
    }

    @Test
    void findContainerNameByIp_matchingIp_returnsContainerName() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);
        when(container.getNames()).thenReturn(new String[]{"/vaier"});

        ContainerNetworkSettings networkSettings = mock(ContainerNetworkSettings.class);
        com.github.dockerjava.api.model.ContainerNetwork network = mock(com.github.dockerjava.api.model.ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn("172.20.0.3");
        when(networkSettings.getNetworks()).thenReturn(Map.of("vaier-network", network));
        when(container.getNetworkSettings()).thenReturn(networkSettings);

        when(listCmd.exec()).thenReturn(List.of(container));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        Optional<String> name = adapter.findContainerNameByIp(Server.local(), "172.20.0.3");

        assertThat(name).contains("vaier");
    }

    @Test
    void getServicesWithExposedPorts_bridgeNetworkContainer_mapsPublicAndPrivatePorts() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("c1");
        when(container.getNames()).thenReturn(new String[]{"/nginx"});
        when(container.getImage()).thenReturn("nginx:1.25");
        when(container.getImageId()).thenReturn("sha256:n1");
        when(container.getState()).thenReturn("running");

        ContainerHostConfig hostConfig = mock(ContainerHostConfig.class);
        when(hostConfig.getNetworkMode()).thenReturn("bridge");
        when(container.getHostConfig()).thenReturn(hostConfig);

        ContainerPort port = new ContainerPort()
            .withIp("0.0.0.0").withPrivatePort(80).withPublicPort(8080).withType("tcp");
        when(container.getPorts()).thenReturn(new ContainerPort[]{port});

        ContainerNetworkSettings networkSettings = mock(ContainerNetworkSettings.class);
        when(networkSettings.getNetworks())
            .thenReturn(Map.of("bridge", mock(com.github.dockerjava.api.model.ContainerNetwork.class)));
        when(container.getNetworkSettings()).thenReturn(networkSettings);

        when(listCmd.exec()).thenReturn(List.of(container));

        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:n1")).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(InspectImageResponse.class));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.local());

        assertThat(services).hasSize(1);
        DockerService.PortMapping mapping = services.get(0).ports().get(0);
        assertThat(mapping.privatePort()).isEqualTo(80);
        assertThat(mapping.publicPort()).isEqualTo(8080);
        assertThat(mapping.type()).isEqualTo("tcp");
        assertThat(mapping.ip()).isEqualTo("0.0.0.0");
    }

    @Test
    void getServicesWithExposedPorts_multiplePortMappings_areAllReturned() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("c1");
        when(container.getNames()).thenReturn(new String[]{"/traefik"});
        when(container.getImage()).thenReturn("traefik:v3");
        when(container.getImageId()).thenReturn("sha256:t1");
        when(container.getState()).thenReturn("running");

        ContainerHostConfig hostConfig = mock(ContainerHostConfig.class);
        when(hostConfig.getNetworkMode()).thenReturn("bridge");
        when(container.getHostConfig()).thenReturn(hostConfig);

        ContainerPort http = new ContainerPort()
            .withIp("0.0.0.0").withPrivatePort(80).withPublicPort(80).withType("tcp");
        ContainerPort https = new ContainerPort()
            .withIp("0.0.0.0").withPrivatePort(443).withPublicPort(443).withType("tcp");
        ContainerPort dashboard = new ContainerPort()
            .withIp("127.0.0.1").withPrivatePort(8080).withPublicPort(8080).withType("tcp");
        when(container.getPorts()).thenReturn(new ContainerPort[]{http, https, dashboard});

        ContainerNetworkSettings networkSettings = mock(ContainerNetworkSettings.class);
        when(networkSettings.getNetworks())
            .thenReturn(Map.of("bridge", mock(com.github.dockerjava.api.model.ContainerNetwork.class)));
        when(container.getNetworkSettings()).thenReturn(networkSettings);

        when(listCmd.exec()).thenReturn(List.of(container));

        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:t1")).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(InspectImageResponse.class));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.local());

        assertThat(services).hasSize(1);
        assertThat(services.get(0).ports()).hasSize(3);
        assertThat(services.get(0).ports())
            .extracting(DockerService.PortMapping::privatePort)
            .containsExactlyInAnyOrder(80, 443, 8080);
    }

    @Test
    void getServicesWithExposedPorts_udpPortType_isPreserved() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("c1");
        when(container.getNames()).thenReturn(new String[]{"/wireguard"});
        when(container.getImage()).thenReturn("lscr.io/linuxserver/wireguard:latest");
        when(container.getImageId()).thenReturn("sha256:w1");
        when(container.getState()).thenReturn("running");

        ContainerHostConfig hostConfig = mock(ContainerHostConfig.class);
        when(hostConfig.getNetworkMode()).thenReturn("bridge");
        when(container.getHostConfig()).thenReturn(hostConfig);

        ContainerPort udpPort = new ContainerPort()
            .withIp("0.0.0.0").withPrivatePort(51820).withPublicPort(51820).withType("udp");
        when(container.getPorts()).thenReturn(new ContainerPort[]{udpPort});

        ContainerNetworkSettings networkSettings = mock(ContainerNetworkSettings.class);
        when(networkSettings.getNetworks())
            .thenReturn(Map.of("bridge", mock(com.github.dockerjava.api.model.ContainerNetwork.class)));
        when(container.getNetworkSettings()).thenReturn(networkSettings);

        when(listCmd.exec()).thenReturn(List.of(container));

        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:w1")).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(InspectImageResponse.class));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.local());

        assertThat(services).hasSize(1);
        assertThat(services.get(0).ports().get(0).type()).isEqualTo("udp");
        assertThat(services.get(0).ports().get(0).privatePort()).isEqualTo(51820);
    }

    @Test
    void getServicesWithExposedPorts_containerWithNoPorts_isExcluded() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);

        ContainerHostConfig hostConfig = mock(ContainerHostConfig.class);
        when(hostConfig.getNetworkMode()).thenReturn("bridge");
        when(container.getHostConfig()).thenReturn(hostConfig);

        when(container.getPorts()).thenReturn(new ContainerPort[0]);

        when(listCmd.exec()).thenReturn(List.of(container));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.local());

        assertThat(services).isEmpty();
    }

    @Test
    void getServicesWithExposedPorts_nullPortsArray_excludesContainer() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);

        ContainerHostConfig hostConfig = mock(ContainerHostConfig.class);
        when(hostConfig.getNetworkMode()).thenReturn("bridge");
        when(container.getHostConfig()).thenReturn(hostConfig);

        when(container.getPorts()).thenReturn(null);

        when(listCmd.exec()).thenReturn(List.of(container));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.local());

        assertThat(services).isEmpty();
    }

    @Test
    void getServicesWithExposedPorts_imageInspectFails_fallsBackToTagFromImageString() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("c1");
        when(container.getNames()).thenReturn(new String[]{"/nginx"});
        when(container.getImage()).thenReturn("nginx:1.27.0");
        when(container.getImageId()).thenReturn("sha256:gone");
        when(container.getState()).thenReturn("running");

        ContainerHostConfig hostConfig = mock(ContainerHostConfig.class);
        when(hostConfig.getNetworkMode()).thenReturn("bridge");
        when(container.getHostConfig()).thenReturn(hostConfig);

        ContainerPort port = new ContainerPort()
            .withIp("0.0.0.0").withPrivatePort(80).withPublicPort(80).withType("tcp");
        when(container.getPorts()).thenReturn(new ContainerPort[]{port});

        ContainerNetworkSettings networkSettings = mock(ContainerNetworkSettings.class);
        when(networkSettings.getNetworks())
            .thenReturn(Map.of("bridge", mock(com.github.dockerjava.api.model.ContainerNetwork.class)));
        when(container.getNetworkSettings()).thenReturn(networkSettings);

        when(listCmd.exec()).thenReturn(List.of(container));

        // Image inspection fails (image was deleted between list and inspect)
        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:gone")).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenThrow(new DockerException("No such image", 404));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.local());

        // Falls back to extracting "1.27.0" from the image tag
        assertThat(services).hasSize(1);
        assertThat(services.get(0).version()).isEqualTo("1.27.0");
    }

    @Test
    void getServicesWithExposedPorts_listContainersFails_throwsRuntimeException() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);
        when(listCmd.exec()).thenThrow(new DockerException("Cannot connect to Docker daemon", 500));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);

        assertThatThrownBy(() -> adapter.getServicesWithExposedPorts(Server.local()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to get Docker services");
    }

    @Test
    void getServicesWithExposedPorts_emptyContainerList_returnsEmptyList() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of());

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.local());

        assertThat(services).isEmpty();
    }

    @Test
    void findContainerNameByIp_blankIp_returnsEmpty() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);

        assertThat(adapter.findContainerNameByIp(Server.local(), "")).isEmpty();
        assertThat(adapter.findContainerNameByIp(Server.local(), "   ")).isEmpty();
        assertThat(adapter.findContainerNameByIp(Server.local(), null)).isEmpty();
    }

    @Test
    void findContainerNameByIp_dockerListFails_returnsEmpty() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);
        when(listCmd.exec()).thenThrow(new DockerException("daemon down", 500));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);

        assertThat(adapter.findContainerNameByIp(Server.local(), "172.20.0.3")).isEmpty();
    }

    @Test
    void findContainerNameByIp_noMatchingIp_returnsEmpty() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);

        ContainerNetworkSettings networkSettings = mock(ContainerNetworkSettings.class);
        com.github.dockerjava.api.model.ContainerNetwork network = mock(com.github.dockerjava.api.model.ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn("172.20.0.3");
        when(networkSettings.getNetworks()).thenReturn(Map.of("vaier-network", network));
        when(container.getNetworkSettings()).thenReturn(networkSettings);

        when(listCmd.exec()).thenReturn(List.of(container));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        Optional<String> name = adapter.findContainerNameByIp(Server.local(), "10.13.13.3");

        assertThat(name).isEmpty();
    }
}
