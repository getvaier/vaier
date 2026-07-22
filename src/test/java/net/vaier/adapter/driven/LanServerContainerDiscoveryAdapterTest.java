package net.vaier.adapter.driven;

import net.vaier.domain.DockerService;
import net.vaier.domain.LanServer;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.UpdateAvailability;
import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.port.ForGettingServerInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanServerContainerDiscoveryAdapterTest {

    @Mock ForGettingLanServers forGettingLanServers;
    @Mock ForGettingServerInfo forGettingServerInfo;

    @InjectMocks LanServerContainerDiscoveryAdapter adapter;

    private static LanServerView dockerHost(String name, String relay) {
        return new LanServerView(new LanServer(name, "192.168.3.50", true, 2375), relay);
    }

    private static DockerService container(String name) {
        return new DockerService("id-" + name, name, "img:latest", "v",
            List.of(new PortMapping(80, 8080, "tcp", "0.0.0.0")), List.of(), "running",
            "sha256:x", UpdateAvailability.UNKNOWN);
    }

    @Test
    void discoverAll_skipsNonDockerHostsAndScrapesTheRest() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "apalveien5"),
            dockerHost("nas", "apalveien5")));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of(container("app")));

        assertThat(adapter.discoverAllLanServerContainers())
            .extracting(LanServerContainers::name, LanServerContainers::status)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("nas", "OK"));
    }

    @Test
    void scrape_withoutARelayAnchor_reportsUnreachableWithoutScraping() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(dockerHost("nas", null)));

        assertThat(adapter.discoverAllLanServerContainers())
            .extracting(LanServerContainers::name, LanServerContainers::status)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("nas", "UNREACHABLE"));
    }

    @Test
    void scrape_whenDockerQueryThrows_reportsUnreachable() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(dockerHost("nas", "apalveien5")));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenThrow(new RuntimeException("connection refused"));

        assertThat(adapter.discoverAllLanServerContainers())
            .extracting(LanServerContainers::status)
            .containsExactly("UNREACHABLE");
    }

    @Test
    void discoverForHost_unknownName_throws() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(dockerHost("nas", "apalveien5")));

        assertThatThrownBy(() -> adapter.discoverLanServerContainersForHost("ghost"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void discoverForHost_runsDockerFalse_throws() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "apalveien5")));

        assertThatThrownBy(() -> adapter.discoverLanServerContainersForHost("printer"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void discoverForHost_docker_returnsScrape() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(dockerHost("nas", "apalveien5")));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of(container("app")));

        assertThat(adapter.discoverLanServerContainersForHost("nas").status()).isEqualTo("OK");
    }
}
