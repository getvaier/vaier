package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerHostConfig;
import com.github.dockerjava.api.model.ContainerNetworkSettings;
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
