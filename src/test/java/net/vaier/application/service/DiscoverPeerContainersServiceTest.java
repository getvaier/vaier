package net.vaier.application.service;

import net.vaier.application.DiscoverPeerContainersUseCase.PeerContainers;
import net.vaier.domain.DockerService;
import net.vaier.domain.Server;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoverPeerContainersServiceTest {

    @Mock
    ForGettingVpnClients forGettingVpnClients;

    @Mock
    ForResolvingPeerNames forResolvingPeerNames;

    @Mock
    ForGettingServerInfo forGettingServerInfo;

    @InjectMocks
    DiscoverPeerContainersService service;

    @Test
    void discoverAll_noClients_returnsEmpty() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of());

        assertThat(service.discoverAll()).isEmpty();
    }

    @Test
    void discoverAll_reachablePeer_returnsStatusOkWithContainers() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        List<DockerService> containers = List.of(dockerService("my-app", 8080));
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(containers);

        List<PeerContainers> result = service.discoverAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("OK");
        assertThat(result.get(0).peerName()).isEqualTo("alice");
        assertThat(result.get(0).vpnIp()).isEqualTo("10.13.13.2");
        assertThat(result.get(0).containers()).isSameAs(containers);
    }

    @Test
    void discoverAll_unreachablePeer_returnsStatusUnreachableWithEmptyContainers() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.3/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.3")).thenReturn("bob");
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class)))
            .thenThrow(new RuntimeException("Connection refused"));

        List<PeerContainers> result = service.discoverAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("UNREACHABLE");
        assertThat(result.get(0).peerName()).isEqualTo("bob");
        assertThat(result.get(0).containers()).isEmpty();
    }

    @Test
    void discoverAll_mixedPeers_handlesEachIndependently() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            client("10.13.13.2/32"),
            client("10.13.13.3/32")
        ));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.3")).thenReturn("bob");
        when(forGettingServerInfo.getServicesWithExposedPorts(argThat(s -> "10.13.13.2".equals(s.getAddress()))))
            .thenReturn(List.of(dockerService("app", 8080)));
        when(forGettingServerInfo.getServicesWithExposedPorts(argThat(s -> "10.13.13.3".equals(s.getAddress()))))
            .thenThrow(new RuntimeException("timeout"));

        List<PeerContainers> result = service.discoverAll();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PeerContainers::status).containsExactly("OK", "UNREACHABLE");
    }

    @Test
    void discoverAll_extractsIpFromCidrNotation() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.5/24")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.5")).thenReturn("charlie");
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.discoverAll();

        assertThat(result.get(0).vpnIp()).isEqualTo("10.13.13.5");
    }

    private VpnClient client(String allowedIps) {
        return new VpnClient("pubkey", allowedIps, "1.2.3.4", "51820", "0", "0", "0");
    }

    private DockerService dockerService(String name, int port) {
        return new DockerService("id123", name, "image:latest",
            List.of(new DockerService.PortMapping(port, port, "tcp", "0.0.0.0")));
    }
}
