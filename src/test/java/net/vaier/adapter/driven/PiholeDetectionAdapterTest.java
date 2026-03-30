package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ContainerNetworkSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PiholeDetectionAdapterTest {

    @Mock DockerClient dockerClient;
    @Mock ListContainersCmd listContainersCmd;

    PiholeDetectionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PiholeDetectionAdapter(dockerClient);
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(true)).thenReturn(listContainersCmd);
    }

    @Test
    void detectPiholeIp_returnsPiholeContainerIp_whenPiholeIsRunning() {
        Container piholeContainer = containerWithImage("pihole/pihole:latest", "172.20.0.100");
        when(listContainersCmd.exec()).thenReturn(List.of(piholeContainer));

        Optional<String> result = adapter.detectPiholeIp();

        assertThat(result).contains("172.20.0.100");
    }

    @Test
    void detectPiholeIp_returnsEmpty_whenNoPiholeContainerExists() {
        Container otherContainer = mock(Container.class);
        when(otherContainer.getImage()).thenReturn("nginx:latest");
        when(listContainersCmd.exec()).thenReturn(List.of(otherContainer));

        Optional<String> result = adapter.detectPiholeIp();

        assertThat(result).isEmpty();
    }

    @Test
    void detectPiholeIp_returnsEmpty_whenContainerListIsEmpty() {
        when(listContainersCmd.exec()).thenReturn(List.of());

        Optional<String> result = adapter.detectPiholeIp();

        assertThat(result).isEmpty();
    }

    @Test
    void detectPiholeIp_matchesPartialImageName_likeOfficialAndTaggedImages() {
        Container tagged = containerWithImage("pihole/pihole:2024.07.0", "172.20.0.101");
        when(listContainersCmd.exec()).thenReturn(List.of(tagged));

        Optional<String> result = adapter.detectPiholeIp();

        assertThat(result).contains("172.20.0.101");
    }

    @Test
    void detectPiholeIp_returnsEmpty_whenDockerThrows() {
        when(listContainersCmd.exec()).thenThrow(new RuntimeException("Docker socket unavailable"));

        Optional<String> result = adapter.detectPiholeIp();

        assertThat(result).isEmpty();
    }

    // --- helpers ---

    private Container containerWithImage(String image, String ip) {
        Container container = mock(Container.class);
        when(container.getImage()).thenReturn(image);

        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn(ip);

        ContainerNetworkSettings networkSettings = mock(ContainerNetworkSettings.class);
        when(networkSettings.getNetworks()).thenReturn(Map.of("vaier-network", network));

        when(container.getNetworkSettings()).thenReturn(networkSettings);
        return container;
    }
}
