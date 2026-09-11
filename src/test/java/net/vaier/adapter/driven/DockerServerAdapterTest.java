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
import net.vaier.domain.ComposeCoordinates;
import net.vaier.domain.ContainerHealth;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

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
        Optional<String> name = adapter.findContainerNameByIp(Server.vaierServer(), "172.20.0.3");

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
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

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
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

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
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

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
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

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
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

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
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

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

        assertThatThrownBy(() -> adapter.getServicesWithExposedPorts(Server.vaierServer()))
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
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

        assertThat(services).isEmpty();
    }

    @Test
    void findContainerNameByIp_blankIp_returnsEmpty() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);

        assertThat(adapter.findContainerNameByIp(Server.vaierServer(), "")).isEmpty();
        assertThat(adapter.findContainerNameByIp(Server.vaierServer(), "   ")).isEmpty();
        assertThat(adapter.findContainerNameByIp(Server.vaierServer(), null)).isEmpty();
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

        assertThat(adapter.findContainerNameByIp(Server.vaierServer(), "172.20.0.3")).isEmpty();
    }

    @Test
    void getServicesWithExposedPorts_hostNetworkRoonServer_collapsesExposedPortRange() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("roon123");
        when(container.getNames()).thenReturn(new String[]{"/roonserver"});
        when(container.getImage()).thenReturn("ghcr.io/roonlabs/roonserver:latest");
        when(container.getImageId()).thenReturn("sha256:roon");
        when(container.getState()).thenReturn("running");

        ContainerHostConfig hostConfig = mock(ContainerHostConfig.class);
        when(hostConfig.getNetworkMode()).thenReturn("host");
        when(container.getHostConfig()).thenReturn(hostConfig);

        ContainerNetworkSettings networkSettings = mock(ContainerNetworkSettings.class);
        when(networkSettings.getNetworks()).thenReturn(Map.of("host", mock(com.github.dockerjava.api.model.ContainerNetwork.class)));
        when(container.getNetworkSettings()).thenReturn(networkSettings);

        when(listCmd.exec()).thenReturn(List.of(container));

        // Build the same ExposedPorts shape Roon ships: 9100-9339/tcp + 55000/tcp + 9003/udp
        StringBuilder exposed = new StringBuilder("\"55000/tcp\":{},\"9003/udp\":{}");
        for (int p = 9100; p <= 9339; p++) {
            exposed.append(",\"").append(p).append("/tcp\":{}");
        }
        String inspectJson = "{\"Config\":{\"ExposedPorts\":{" + exposed + "}}}";
        DockerHttpClient.Response httpResponse = mock(DockerHttpClient.Response.class);
        when(httpResponse.getBody()).thenReturn(new ByteArrayInputStream(inspectJson.getBytes(StandardCharsets.UTF_8)));
        when(dockerHttpClient.execute(any(DockerHttpClient.Request.class))).thenReturn(httpResponse);

        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:roon")).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(InspectImageResponse.class));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

        assertThat(services).hasSize(1);
        // 163 raw entries collapse to 3: one 9100-9339/tcp range + 55000/tcp + 9003/udp
        assertThat(services.get(0).ports()).hasSize(3);
        assertThat(services.get(0).ports()).filteredOn(DockerService.PortMapping::isRange)
            .singleElement()
            .satisfies(r -> {
                assertThat(r.privatePort()).isEqualTo(9100);
                assertThat(r.lastPrivatePort()).isEqualTo(9339);
                assertThat(r.type()).isEqualTo("tcp");
            });
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
        Optional<String> name = adapter.findContainerNameByIp(Server.vaierServer(), "10.13.13.3");

        assertThat(name).isEmpty();
    }

    /**
     * #57: the local digest must come from RepoDigests. getImageId() is the image's *config* sha and never
     * matches what a registry serves for a tag — comparing it would flag every container forever.
     */
    @Test
    void getServicesWithExposedPorts_capturesTheLocalRegistryDigestFromRepoDigests() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("c1");
        when(container.getNames()).thenReturn(new String[]{"/vaultwarden"});
        when(container.getImage()).thenReturn("vaultwarden/server:latest");
        when(container.getImageId()).thenReturn("sha256:configsha");
        when(container.getState()).thenReturn("running");
        ContainerPort exposed = port(80, 8080);
        when(container.getPorts()).thenReturn(new ContainerPort[]{exposed});
        when(listCmd.exec()).thenReturn(List.of(container));

        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:configsha")).thenReturn(inspectImageCmd);
        InspectImageResponse imageResponse = mock(InspectImageResponse.class);
        when(imageResponse.getRepoDigests())
            .thenReturn(List.of("vaultwarden/server@sha256:registrydigest"));
        when(inspectImageCmd.exec()).thenReturn(imageResponse);

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

        assertThat(services).singleElement()
            .extracting(DockerService::imageDigest).isEqualTo("sha256:registrydigest");
    }

    @Test
    void getServicesWithExposedPorts_locallyBuiltImageHasNoDigestAndDoesNotFailTheScrape() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("c2");
        when(container.getNames()).thenReturn(new String[]{"/my-build"});
        when(container.getImage()).thenReturn("my-build:latest");
        when(container.getImageId()).thenReturn("sha256:configsha");
        when(container.getState()).thenReturn("running");
        ContainerPort exposed = port(80, 8080);
        when(container.getPorts()).thenReturn(new ContainerPort[]{exposed});
        when(listCmd.exec()).thenReturn(List.of(container));

        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:configsha")).thenReturn(inspectImageCmd);
        InspectImageResponse imageResponse = mock(InspectImageResponse.class);
        when(imageResponse.getRepoDigests()).thenReturn(List.of());
        when(inspectImageCmd.exec()).thenReturn(imageResponse);

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

        assertThat(services).singleElement().extracting(DockerService::imageDigest).isNull();
    }

    @Test
    void getServicesWithExposedPorts_aFailedImageInspectLeavesTheDigestNullRatherThanFailing() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("c3");
        when(container.getNames()).thenReturn(new String[]{"/app"});
        when(container.getImage()).thenReturn("some/app:1.0");
        when(container.getImageId()).thenReturn("sha256:gone");
        when(container.getState()).thenReturn("running");
        ContainerPort exposed = port(80, 8080);
        when(container.getPorts()).thenReturn(new ContainerPort[]{exposed});
        when(listCmd.exec()).thenReturn(List.of(container));

        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:gone")).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenThrow(new RuntimeException("no such image"));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

        assertThat(services).singleElement().extracting(DockerService::imageDigest).isNull();
        assertThat(services).singleElement().extracting(DockerService::containerName).isEqualTo("app");
    }

    @Test
    void getServicesWithExposedPorts_readsHowComposeStartedTheContainerOffItsLabels() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("c4");
        when(container.getNames()).thenReturn(new String[]{"/pihole"});
        when(container.getImage()).thenReturn("pihole/pihole:latest");
        when(container.getImageId()).thenReturn("sha256:p1");
        when(container.getState()).thenReturn("running");
        when(container.getLabels()).thenReturn(Map.of(
            "com.docker.compose.project", "pihole",
            "com.docker.compose.service", "pihole",
            "com.docker.compose.project.config_files", "/home/ubuntu/pihole/docker-compose.yml",
            "com.docker.compose.project.working_dir", "/home/ubuntu/pihole"));
        ContainerPort piholePort = new ContainerPort()
            .withIp("0.0.0.0").withPrivatePort(80).withPublicPort(8081).withType("tcp");
        when(container.getPorts()).thenReturn(new ContainerPort[]{piholePort});
        when(listCmd.exec()).thenReturn(List.of(container));

        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:p1")).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(InspectImageResponse.class));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

        ComposeCoordinates coordinates = services.get(0).composeCoordinates();
        assertThat(coordinates).isNotNull();
        assertThat(coordinates.project()).isEqualTo("pihole");
        assertThat(coordinates.service()).isEqualTo("pihole");
        assertThat(coordinates.configFiles()).containsExactly("/home/ubuntu/pihole/docker-compose.yml");
    }

    @Test
    void getServicesWithExposedPorts_aContainerWithNoComposeLabelsCarriesNoCoordinates() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("c5");
        when(container.getNames()).thenReturn(new String[]{"/hand-started"});
        when(container.getImage()).thenReturn("some/app:1.0");
        when(container.getImageId()).thenReturn("sha256:h1");
        when(container.getState()).thenReturn("running");
        ContainerPort appPort = new ContainerPort()
            .withIp("0.0.0.0").withPrivatePort(80).withPublicPort(8082).withType("tcp");
        when(container.getPorts()).thenReturn(new ContainerPort[]{appPort});
        when(listCmd.exec()).thenReturn(List.of(container));

        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:h1")).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(InspectImageResponse.class));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

        assertThat(services).singleElement().extracting(DockerService::composeCoordinates).isNull();
    }

    private static ContainerPort port(int privatePort, int publicPort) {
        ContainerPort p = mock(ContainerPort.class);
        when(p.getPrivatePort()).thenReturn(privatePort);
        when(p.getPublicPort()).thenReturn(publicPort);
        when(p.getType()).thenReturn("tcp");
        when(p.getIp()).thenReturn("0.0.0.0");
        return p;
    }

    // --- a container that is stopped but still publishes ports (#356) -------------------------------
    //
    // Docker reports NO port mappings at all for a stopped container, so keeping only containers with
    // mappings dropped every exited container from every scrape. Nothing downstream could tell "stopped"
    // from "removed" — which is exactly the distinction the container standing is built on — and the
    // Explorer's own DOWN badge could never have been drawn either.

    /** A bridge-network container the daemon lists in whatever state the test wants. */
    private static Container bridgeContainer(String name, String state, ContainerPort[] ports) {
        Container container = mock(Container.class);
        // Lenient: a container that publishes nothing never makes it far enough to be named.
        lenient().when(container.getNames()).thenReturn(new String[]{"/" + name});
        when(container.getState()).thenReturn(state);
        when(container.getPorts()).thenReturn(ports);
        ContainerHostConfig hostConfig = mock(ContainerHostConfig.class);
        when(hostConfig.getNetworkMode()).thenReturn("bridge");
        when(container.getHostConfig()).thenReturn(hostConfig);
        return container;
    }

    private static void answersInspectWith(DockerHttpClient dockerHttpClient, String json) {
        DockerHttpClient.Response httpResponse = mock(DockerHttpClient.Response.class);
        when(httpResponse.getBody())
            .thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        when(dockerHttpClient.execute(any(DockerHttpClient.Request.class))).thenReturn(httpResponse);
    }

    @Test
    void getServicesWithExposedPorts_stoppedContainerThatPublishesPorts_isStillDiscovered() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        // What the daemon actually reports for a stopped container: no port mappings whatsoever.
        Container container = bridgeContainer("webtrees", "exited", new ContainerPort[]{});
        when(container.getId()).thenReturn("abc123");
        when(container.getImage()).thenReturn("ghcr.io/webtrees:2.1");
        when(container.getImageId()).thenReturn("sha256:abc");
        when(listCmd.exec()).thenReturn(List.of(container));

        // The published bindings survive the stop — they are what the container WAS published on.
        answersInspectWith(dockerHttpClient, """
            {"Config":{"ExposedPorts":{"9999/tcp":{}}},
             "HostConfig":{"PortBindings":{"80/tcp":[{"HostIp":"0.0.0.0","HostPort":"8081"}]}}}
            """);

        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:abc")).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(InspectImageResponse.class));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

        assertThat(services).singleElement().satisfies(service -> {
            assertThat(service.containerName()).isEqualTo("webtrees");
            // The real state, never smoothed over: the domain is what decides what "exited" means.
            assertThat(service.state()).isEqualTo("exited");
            assertThat(service.isRunning()).isFalse();
            assertThat(service.ports()).singleElement().satisfies(port -> {
                assertThat(port.privatePort()).isEqualTo(80);
                assertThat(port.publicPort()).isEqualTo(8081);
                assertThat(port.type()).isEqualTo("tcp");
            });
        });
        // Deliberately NOT Config.ExposedPorts: that is the image's own EXPOSE list, and 9999 was never
        // published. Reading it would invent published ports nobody ever asked for.
        assertThat(services.get(0).ports())
            .noneMatch(port -> port.privatePort() == 9999);
    }

    @Test
    void getServicesWithExposedPorts_stoppedContainerThatPublishedNothing_staysOutOfTheScrape() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = bridgeContainer("dex-init", "exited", new ContainerPort[]{});
        when(container.getId()).thenReturn("def456");
        when(listCmd.exec()).thenReturn(List.of(container));
        answersInspectWith(dockerHttpClient, """
            {"Config":{"ExposedPorts":{"5556/tcp":{}}},"HostConfig":{"PortBindings":{}}}
            """);

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);

        // This scrape has always been "containers with published ports"; a one-shot init container that
        // published nothing is no more interesting stopped than it was running.
        assertThat(adapter.getServicesWithExposedPorts(Server.vaierServer())).isEmpty();
    }

    @Test
    void getServicesWithExposedPorts_runningContainer_isNeverInspectedForItsPorts() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = bridgeContainer("vaultwarden", "running",
            new ContainerPort[]{containerPort(80, 8080, "tcp", "0.0.0.0")});
        when(container.getId()).thenReturn("ghi789");
        when(container.getImage()).thenReturn("vaultwarden/server:latest");
        when(container.getImageId()).thenReturn("sha256:vw");
        when(listCmd.exec()).thenReturn(List.of(container));

        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:vw")).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(InspectImageResponse.class));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        assertThat(adapter.getServicesWithExposedPorts(Server.vaierServer())).hasSize(1);

        // The running path is unchanged: the daemon's own listing already carries every mapping, so a
        // fleet-wide scrape must not grow one inspect per running container.
        verifyNoInteractions(dockerHttpClient);
    }

    private static ContainerPort containerPort(int privatePort, Integer publicPort, String type, String ip) {
        ContainerPort port = mock(ContainerPort.class);
        when(port.getPrivatePort()).thenReturn(privatePort);
        when(port.getPublicPort()).thenReturn(publicPort);
        when(port.getType()).thenReturn(type);
        when(port.getIp()).thenReturn(ip);
        return port;
    }

    // --- what a container's own health check says (#317) -----------------------------------------------
    //
    // Off the listing, never an inspect. Docker writes the verdict into the status line it already returns
    // for every container, so the fleet scrape that lists them has it in hand — and an inspect per running
    // container per machine every 30 seconds, for a fact already on the wire, is the thing not to build.

    @Test
    void getServicesWithExposedPorts_aFailingHealthCheck_isReadOffTheListingsStatus() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = bridgeContainer("webtrees", "running",
            new ContainerPort[]{containerPort(80, 8080, "tcp", "0.0.0.0")});
        when(container.getId()).thenReturn("wt1");
        when(container.getImage()).thenReturn("ghcr.io/webtrees:2.1");
        when(container.getImageId()).thenReturn("sha256:wt");
        when(container.getStatus()).thenReturn("Up 3 minutes (unhealthy)");
        when(listCmd.exec()).thenReturn(List.of(container));

        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:wt")).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(InspectImageResponse.class));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);
        List<DockerService> services = adapter.getServicesWithExposedPorts(Server.vaierServer());

        assertThat(services).singleElement()
            .satisfies(service -> assertThat(service.health()).isEqualTo(ContainerHealth.UNHEALTHY));
        // And still nothing asked of the daemon beyond the listing.
        verifyNoInteractions(dockerHttpClient);
    }

    @Test
    void getServicesWithExposedPorts_aContainerWithNoHealthCheck_carriesNoVerdict() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerHttpClient dockerHttpClient = mock(DockerHttpClient.class);

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        Container container = bridgeContainer("traefik", "running",
            new ContainerPort[]{containerPort(80, 80, "tcp", "0.0.0.0")});
        when(container.getId()).thenReturn("tk1");
        when(container.getImage()).thenReturn("traefik:v3");
        when(container.getImageId()).thenReturn("sha256:tk");
        when(container.getStatus()).thenReturn("Up 5 days");
        when(listCmd.exec()).thenReturn(List.of(container));

        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd("sha256:tk")).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(InspectImageResponse.class));

        DockerServerAdapter adapter = new DockerServerAdapter(dockerClient, dockerHttpClient);

        assertThat(adapter.getServicesWithExposedPorts(Server.vaierServer())).singleElement()
            .satisfies(service -> assertThat(service.health()).isEqualTo(ContainerHealth.NONE));
    }
}
