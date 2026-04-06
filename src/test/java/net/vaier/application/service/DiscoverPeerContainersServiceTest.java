package net.vaier.application.service;

import net.vaier.application.DiscoverPeerContainersUseCase.PeerContainers;
import net.vaier.domain.DockerService;
import net.vaier.domain.PeerType;
import net.vaier.domain.Server;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoverPeerContainersServiceTest {

    @Mock
    ForGettingVpnClients forGettingVpnClients;

    @Mock
    ForResolvingPeerNames forResolvingPeerNames;

    @Mock
    ForGettingServerInfo forGettingServerInfo;

    @Mock
    ForGettingPeerConfigurations forGettingPeerConfigurations;

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
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", PeerType.UBUNTU_SERVER)));
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
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.3"))
            .thenReturn(Optional.of(peerConfig("bob", "10.13.13.3", PeerType.UBUNTU_SERVER)));
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
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", PeerType.UBUNTU_SERVER)));
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.3"))
            .thenReturn(Optional.of(peerConfig("bob", "10.13.13.3", PeerType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(argThat(s -> s != null && "10.13.13.2".equals(s.getAddress()))))
            .thenReturn(List.of(dockerService("app", 8080)));
        when(forGettingServerInfo.getServicesWithExposedPorts(argThat(s -> s != null && "10.13.13.3".equals(s.getAddress()))))
            .thenThrow(new RuntimeException("timeout"));

        List<PeerContainers> result = service.discoverAll();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PeerContainers::status).containsExactly("OK", "UNREACHABLE");
    }

    @Test
    void discoverAll_extractsIpFromCidrNotation() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.5/24")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.5")).thenReturn("charlie");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.5"))
            .thenReturn(Optional.of(peerConfig("charlie", "10.13.13.5", PeerType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.discoverAll();

        assertThat(result.get(0).vpnIp()).isEqualTo("10.13.13.5");
    }

    @Test
    void discoverAll_mobileClient_isSkipped() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.10/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.10")).thenReturn("phone");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.10"))
            .thenReturn(Optional.of(peerConfig("phone", "10.13.13.10", PeerType.MOBILE_CLIENT)));

        List<PeerContainers> result = service.discoverAll();

        assertThat(result).isEmpty();
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_windowsClient_isSkipped() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.11/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.11")).thenReturn("laptop");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.11"))
            .thenReturn(Optional.of(peerConfig("laptop", "10.13.13.11", PeerType.WINDOWS_CLIENT)));

        List<PeerContainers> result = service.discoverAll();

        assertThat(result).isEmpty();
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_serverPeer_isQueried() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("server1");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.2", PeerType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.discoverAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("OK");
        verify(forGettingServerInfo).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_serverPeerWithStaleHandshake_skippedWithoutDockerQuery() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(disconnectedClient("10.13.13.5/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.5")).thenReturn("server1");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.5"))
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.5", PeerType.UBUNTU_SERVER)));

        List<PeerContainers> result = service.discoverAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("UNREACHABLE");
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_unknownPeerConfig_defaultsToQueried() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.20/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.20")).thenReturn("unknown");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.20"))
            .thenReturn(Optional.empty());
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.discoverAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("OK");
        verify(forGettingServerInfo).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_mixedTypes_onlyServerPeersQueried() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            client("10.13.13.2/32"),
            client("10.13.13.10/32"),
            client("10.13.13.3/32")
        ));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("server1");
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.10")).thenReturn("phone");
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.3")).thenReturn("server2");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.2", PeerType.UBUNTU_SERVER)));
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.10"))
            .thenReturn(Optional.of(peerConfig("phone", "10.13.13.10", PeerType.MOBILE_CLIENT)));
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.3"))
            .thenReturn(Optional.of(peerConfig("server2", "10.13.13.3", PeerType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.discoverAll();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PeerContainers::peerName).containsExactly("server1", "server2");
    }

    @Test
    void discoverAll_peerWithHandshake240SecondsAgo_isStillQueried() {
        String handshake240sAgo = String.valueOf(System.currentTimeMillis() / 1000 - 240);
        VpnClient peer = new VpnClient("pubkey", "10.13.13.5/32", "1.2.3.4", "51820", handshake240sAgo, "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(peer));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.5")).thenReturn("server1");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.5"))
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.5", PeerType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.discoverAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("OK");
    }

    private VpnClient client(String allowedIps) {
        String recentHandshake = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        return new VpnClient("pubkey", allowedIps, "1.2.3.4", "51820", recentHandshake, "0", "0");
    }

    private VpnClient disconnectedClient(String allowedIps) {
        return new VpnClient("pubkey", allowedIps, "1.2.3.4", "51820", "0", "0", "0");
    }

    private PeerConfiguration peerConfig(String name, String ip, PeerType type) {
        return new PeerConfiguration(name, ip, "", type, null);
    }

    private DockerService dockerService(String name, int port) {
        return new DockerService("id123", name, "image:latest", "latest",
            List.of(new DockerService.PortMapping(port, port, "tcp", "0.0.0.0")), List.of());
    }
}
