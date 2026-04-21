package net.vaier.integration.controller;

import net.vaier.application.DiscoverPeerContainersUseCase.PeerContainers;
import net.vaier.domain.DockerService;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DockerServiceRestControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void getDockerServices_returnsServicesForGivenServer() throws Exception {
        when(getServerInfoUseCase.getServicesWithExposedPorts(any())).thenReturn(List.of(
                new DockerService("abc123", "app", "nginx:latest", "latest",
                        List.of(new PortMapping(80, 8080, "tcp", "0.0.0.0")),
                        List.of("bridge"), "running")
        ));

        mockMvc.perform(get("/docker-services")
                       .param("address", "10.0.0.1")
                       .param("port", "2375")
                       .param("tlsEnabled", "false"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].containerId").value("abc123"))
               .andExpect(jsonPath("$[0].containerName").value("app"))
               .andExpect(jsonPath("$[0].state").value("running"));
    }

    @Test
    void getDockerServices_passesServerParamsToUseCase() throws Exception {
        when(getServerInfoUseCase.getServicesWithExposedPorts(any())).thenReturn(List.of());

        mockMvc.perform(get("/docker-services")
                       .param("address", "10.0.0.2")
                       .param("port", "2376")
                       .param("tlsEnabled", "true"))
               .andExpect(status().isOk());

        ArgumentCaptor<net.vaier.domain.Server> captor = ArgumentCaptor.forClass(net.vaier.domain.Server.class);
        verify(getServerInfoUseCase).getServicesWithExposedPorts(captor.capture());
        assertThat(captor.getValue().getAddress()).isEqualTo("10.0.0.2");
        assertThat(captor.getValue().getPort()).isEqualTo(2376);
        assertThat(captor.getValue().isTlsEnabled()).isTrue();
    }

    @Test
    void getDockerServices_defaultsTlsEnabledToFalse() throws Exception {
        when(getServerInfoUseCase.getServicesWithExposedPorts(any())).thenReturn(List.of());

        mockMvc.perform(get("/docker-services").param("address", "10.0.0.3"))
               .andExpect(status().isOk());

        ArgumentCaptor<net.vaier.domain.Server> captor = ArgumentCaptor.forClass(net.vaier.domain.Server.class);
        verify(getServerInfoUseCase).getServicesWithExposedPorts(captor.capture());
        assertThat(captor.getValue().isTlsEnabled()).isFalse();
        assertThat(captor.getValue().getPort()).isNull();
    }

    @Test
    void getDockerServices_returns400WhenAddressMissing() throws Exception {
        mockMvc.perform(get("/docker-services"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void getDockerServices_returns500WhenDockerUnavailable() throws Exception {
        when(getServerInfoUseCase.getServicesWithExposedPorts(any()))
                .thenThrow(new RuntimeException("Docker host unreachable"));

        mockMvc.perform(get("/docker-services").param("address", "10.0.0.99"))
               .andExpect(status().isInternalServerError());
    }

    @Test
    void discoverLocalContainers_returnsLocalServices() throws Exception {
        when(discoverLocalContainersUseCase.discover()).thenReturn(List.of(
                new DockerService("local1", "vaier", "getvaier/vaier:latest", "latest",
                        List.of(new PortMapping(8080, 8888, "tcp", "0.0.0.0")),
                        List.of("vaier-net"), "running")
        ));

        mockMvc.perform(get("/docker-services/local"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].containerId").value("local1"))
               .andExpect(jsonPath("$[0].containerName").value("vaier"));
    }

    @Test
    void discoverLocalContainers_returnsEmptyListWhenNoContainers() throws Exception {
        when(discoverLocalContainersUseCase.discover()).thenReturn(List.of());

        mockMvc.perform(get("/docker-services/local"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void discoverLocalContainers_returns500WhenDockerUnavailable() throws Exception {
        when(discoverLocalContainersUseCase.discover())
                .thenThrow(new RuntimeException("docker.sock not accessible"));

        mockMvc.perform(get("/docker-services/local"))
               .andExpect(status().isInternalServerError());
    }

    @Test
    void discoverPeerContainers_returnsPeerContainerList() throws Exception {
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
                new PeerContainers("peer1", "10.13.13.2", "OK", List.of(
                        new DockerService("c1", "svc", "img:1.0", "1.0",
                                List.of(), List.of(), "running")
                ))
        ));

        mockMvc.perform(get("/docker-services/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].peerName").value("peer1"))
               .andExpect(jsonPath("$[0].vpnIp").value("10.13.13.2"))
               .andExpect(jsonPath("$[0].status").value("OK"))
               .andExpect(jsonPath("$[0].containers[0].containerName").value("svc"));
    }

    @Test
    void discoverPeerContainers_returnsEmptyListWhenNoPeers() throws Exception {
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of());

        mockMvc.perform(get("/docker-services/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void discoverPeerContainers_returnsUnreachablePeerEntry() throws Exception {
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
                new PeerContainers("peer2", "10.13.13.3", "UNREACHABLE", List.of())
        ));

        mockMvc.perform(get("/docker-services/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].peerName").value("peer2"))
               .andExpect(jsonPath("$[0].status").value("UNREACHABLE"))
               .andExpect(jsonPath("$[0].containers").isEmpty());
    }
}
