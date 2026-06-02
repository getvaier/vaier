package net.vaier.application.service;

import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import net.vaier.domain.PublishableService;
import net.vaier.domain.PublishableService.PublishableSource;
import net.vaier.domain.LanServer;
import net.vaier.config.ServiceNames;
import net.vaier.domain.DockerService;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.MachineType;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Server;
import net.vaier.domain.VpnClient;
import net.vaier.domain.WireguardClientImage;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerServiceTest {

    private static final String VAIER_NETWORK = "vaier-network";
    private static final String GATEWAY_IP = "172.20.0.1";

    @Mock ForGettingServerInfo forGettingServerInfo;
    @Mock ForGettingVpnClients forGettingVpnClients;
    @Mock ForResolvingPeerNames forResolvingPeerNames;
    @Mock ForGettingPeerConfigurations forGettingPeerConfigurations;
    @Mock ForGettingLanServers forGettingLanServers;

    ContainerService service;

    @BeforeEach
    void setUp() {
        service = new ContainerService(forGettingServerInfo, forGettingVpnClients,
            forResolvingPeerNames, forGettingPeerConfigurations, forGettingLanServers,
            VAIER_NETWORK, GATEWAY_IP);
    }

    // --- Vaier-server container scrape + discover cache (local) ---

    @Test
    void scrapeVaierServerContainers_usesLocalDockerSocket() {
        List<DockerService> expected = List.of(dockerService("my-app", 8080));
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("unix:///var/run/docker.sock"))
        )).thenReturn(expected);

        assertThat(service.scrapeVaierServerContainers()).isSameAs(expected);
    }

    @Test
    void scrapeVaierServerContainers_returnsEmptyListWhenNoServicesRunning() {
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("unix:///var/run/docker.sock"))
        )).thenReturn(List.of());

        assertThat(service.scrapeVaierServerContainers()).isEmpty();
    }

    @Test
    void scrapeVaierServerContainers_propagatesExceptionFromAdapter() {
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("unix:///var/run/docker.sock"))
        )).thenThrow(new RuntimeException("socket not found"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> service.scrapeVaierServerContainers());
    }

    @Test
    void discover_beforeRefresh_returnsEmptySnapshot() {
        assertThat(service.discover()).isEmpty();
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void refresh_thenDiscover_servesTheScrapedVaierServerSnapshot() {
        List<DockerService> expected = List.of(dockerService("my-app", 8080));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(expected);

        service.refresh();

        assertThat(service.discover()).isEqualTo(expected);
    }

    @Test
    void refresh_whenVaierScrapeFails_keepsServingThePreviousSnapshot() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenThrow(new RuntimeException("socket not found"));

        service.refresh();

        assertThat(service.discover()).isEmpty();
    }

    // --- getServicesWithExposedPorts ---

    @Test
    void getServicesWithExposedPorts_delegatesToPort() {
        Server server = new Server("10.13.13.2", 2375, false);
        DockerService dockerService = mock(DockerService.class);
        when(forGettingServerInfo.getServicesWithExposedPorts(server)).thenReturn(List.of(dockerService));

        assertThat(service.getServicesWithExposedPorts(server)).containsExactly(dockerService);
    }

    // --- discoverAll (peer containers) ---

    @Test
    void discoverAll_noClients_returnsEmpty() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of());

        assertThat(service.scrapePeerContainers()).isEmpty();
    }

    @Test
    void discoverAll_reachablePeer_returnsStatusOkWithContainers() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        List<DockerService> containers = List.of(dockerService("my-app", 8080));
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class))).thenReturn(containers);

        List<PeerContainers> result = service.scrapePeerContainers();

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
            .thenReturn(Optional.of(peerConfig("bob", "10.13.13.3", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class)))
            .thenThrow(new RuntimeException("Connection refused"));

        List<PeerContainers> result = service.scrapePeerContainers();

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
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.3"))
            .thenReturn(Optional.of(peerConfig("bob", "10.13.13.3", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(argThat(s -> s != null && "10.13.13.2".equals(s.getAddress()))))
            .thenReturn(List.of(dockerService("app", 8080)));
        when(forGettingServerInfo.getServicesWithExposedPorts(argThat(s -> s != null && "10.13.13.3".equals(s.getAddress()))))
            .thenThrow(new RuntimeException("timeout"));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PeerContainers::status).containsExactly("OK", "UNREACHABLE");
    }

    @Test
    void discoverAll_extractsIpFromCidrNotation() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.5/24")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.5")).thenReturn("charlie");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.5"))
            .thenReturn(Optional.of(peerConfig("charlie", "10.13.13.5", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).vpnIp()).isEqualTo("10.13.13.5");
    }

    @Test
    void discoverAll_mobileClient_isSkipped() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.10/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.10")).thenReturn("phone");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.10"))
            .thenReturn(Optional.of(peerConfig("phone", "10.13.13.10", MachineType.MOBILE_CLIENT)));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).isEmpty();
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_windowsClient_isSkipped() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.11/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.11")).thenReturn("laptop");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.11"))
            .thenReturn(Optional.of(peerConfig("laptop", "10.13.13.11", MachineType.WINDOWS_CLIENT)));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).isEmpty();
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_serverPeer_isQueried() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("server1");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("OK");
        verify(forGettingServerInfo).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_serverPeerWithStaleHandshake_skippedWithoutDockerQuery() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(disconnectedClient("10.13.13.5/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.5")).thenReturn("server1");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.5"))
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.5", MachineType.UBUNTU_SERVER)));

        List<PeerContainers> result = service.scrapePeerContainers();

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

        List<PeerContainers> result = service.scrapePeerContainers();

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
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.10"))
            .thenReturn(Optional.of(peerConfig("phone", "10.13.13.10", MachineType.MOBILE_CLIENT)));
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.3"))
            .thenReturn(Optional.of(peerConfig("server2", "10.13.13.3", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PeerContainers::peerName).containsExactly("server1", "server2");
    }

    @Test
    void discoverAll_peerWithHandshakeStalerThan180s_isSkippedAsDisconnected() {
        // Connectivity must follow the single domain rule VpnClient.isConnected() — a peer is
        // connected only while (now - handshake) < 180s. A 240s-stale handshake is disconnected.
        String handshake240sAgo = String.valueOf(System.currentTimeMillis() / 1000 - 240);
        VpnClient peer = new VpnClient("pubkey", "10.13.13.5/32", "1.2.3.4", "51820", handshake240sAgo, "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(peer));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.5")).thenReturn("server1");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.5"))
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.5", MachineType.UBUNTU_SERVER)));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("UNREACHABLE");
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAll_peerWithHandshakeWithin180s_isQueried() {
        String handshake120sAgo = String.valueOf(System.currentTimeMillis() / 1000 - 120);
        VpnClient peer = new VpnClient("pubkey", "10.13.13.5/32", "1.2.3.4", "51820", handshake120sAgo, "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(peer));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.5")).thenReturn("server1");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.5"))
            .thenReturn(Optional.of(peerConfig("server1", "10.13.13.5", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("OK");
    }

    @Test
    void discoverAll_peerWithMatchingWireguardImage_wireguardOutdatedIsFalse() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(wireguardContainer(WireguardClientImage.EXPECTED)));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).wireguardOutdated()).isFalse();
    }

    @Test
    void discoverAll_alwaysReportsExpectedWireguardImageOnReachablePeers() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(wireguardContainer("lscr.io/linuxserver/wireguard:1.0.20210914-ls42")));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).wireguardExpectedImage()).isEqualTo(WireguardClientImage.EXPECTED);
    }

    @Test
    void discoverAll_peerWithOlderWireguardImage_wireguardOutdatedIsTrue() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(wireguardContainer("lscr.io/linuxserver/wireguard:1.0.20210914-ls42")));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).wireguardOutdated()).isTrue();
    }

    @Test
    void discoverAll_peerWithLatestTagWireguard_wireguardOutdatedIsTrue() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(wireguardContainer("lscr.io/linuxserver/wireguard:latest")));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).wireguardOutdated()).isTrue();
    }

    @Test
    void discoverAll_peerWithNoWireguardContainer_wireguardOutdatedIsFalse() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(dockerService("app", 8080)));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).wireguardOutdated()).isFalse();
    }

    @Test
    void discoverAll_unreachablePeer_wireguardOutdatedIsFalse() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.3/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.3")).thenReturn("bob");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.3"))
            .thenReturn(Optional.of(peerConfig("bob", "10.13.13.3", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenThrow(new RuntimeException("Connection refused"));

        List<PeerContainers> result = service.scrapePeerContainers();

        assertThat(result.get(0).wireguardOutdated()).isFalse();
    }

    // --- refresh + discoverAll cache ---

    @Test
    void discoverAll_beforeRefresh_returnsEmptySnapshot() {
        assertThat(service.discoverAll()).isEmpty();
        verify(forGettingVpnClients, never()).getClients();
    }

    @Test
    void refresh_thenDiscoverAll_servesTheScrapedSnapshot() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.refresh();

        List<PeerContainers> result = service.discoverAll();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).peerName()).isEqualTo("alice");
    }

    @Test
    void discoverAll_servesCachedSnapshotWithoutRescraping() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client("10.13.13.2/32")));
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(forGettingPeerConfigurations.getPeerConfigByIp("10.13.13.2"))
            .thenReturn(Optional.of(peerConfig("alice", "10.13.13.2", MachineType.UBUNTU_SERVER)));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of());

        service.refresh();
        service.discoverAll();
        service.discoverAll();
        service.discoverAll();

        // wg is queried once per refresh, not once per read.
        verify(forGettingVpnClients, times(1)).getClients();
    }

    // --- getUnpublishedVaierServerServices ---

    @Test
    void getUnpublishedVaierServerServices_excludesWireguardContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any(Server.class)))
            .thenReturn(List.of(localContainer(ServiceNames.WIREGUARD, 51820, "tcp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_excludesAutheliaContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer(ServiceNames.AUTHELIA, 9091, "tcp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_excludesRedisContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer(ServiceNames.REDIS, 6379, "tcp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_excludesVaierContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer(ServiceNames.VAIER, 8080, "tcp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_excludesWireguardMasqueradeContainer() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer(ServiceNames.WIREGUARD_MASQUERADE, 8080, "tcp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_traefikOnPort8080_includedWithDashboardRedirect() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer(ServiceNames.TRAEFIK, 8080, "tcp")));

        List<PublishableService> result = refreshThenGetUnpublished(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).containerName()).isEqualTo(ServiceNames.TRAEFIK);
        assertThat(result.get(0).port()).isEqualTo(8080);
        assertThat(result.get(0).rootRedirectPath()).isEqualTo("/dashboard/");
        assertThat(result.get(0).source()).isEqualTo(PublishableSource.VAIER_SERVER);
    }

    @Test
    void getUnpublishedVaierServerServices_traefikOnPort80_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer("traefik", 80, "tcp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_unknownContainerTcpPort_includedWithNullRedirectPath() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer("my-app", 3000, "tcp")));

        List<PublishableService> result = refreshThenGetUnpublished(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).containerName()).isEqualTo("my-app");
        assertThat(result.get(0).port()).isEqualTo(3000);
        assertThat(result.get(0).rootRedirectPath()).isNull();
    }

    @Test
    void getUnpublishedVaierServerServices_udpPort_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer("my-app", 3000, "udp")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_alreadyPublishedRoute_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(localContainer("my-app", 3000, "tcp")));
        List<ReverseProxyRoute> existingRoutes = List.of(route("my-app", 3000));

        assertThat(refreshThenGetUnpublished(existingRoutes)).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_dockerThrows_returnsEmptyList() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenThrow(new RuntimeException("Docker socket unavailable"));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_containerOnVaierNetwork_usesContainerNameAndPrivatePort() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(new DockerService("id", "my-app", "image:latest", "latest",
                List.of(new PortMapping(3001, null, "tcp", "0.0.0.0")),
                List.of(VAIER_NETWORK), "running")));

        List<PublishableService> result = refreshThenGetUnpublished(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).address()).isEqualTo("my-app");
        assertThat(result.get(0).port()).isEqualTo(3001);
    }

    @Test
    void getUnpublishedVaierServerServices_containerOnOtherNetworkWithPublicPort_usesGatewayAndPublicPort() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(new DockerService("id", "uptime-kuma", "image:latest", "latest",
                List.of(new PortMapping(3001, 3001, "tcp", "0.0.0.0")),
                List.of("uptime-kuma_default"), "running")));

        List<PublishableService> result = refreshThenGetUnpublished(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).address()).isEqualTo(GATEWAY_IP);
        assertThat(result.get(0).port()).isEqualTo(3001);
        assertThat(result.get(0).containerName()).isEqualTo("uptime-kuma");
    }

    @Test
    void getUnpublishedVaierServerServices_containerOnOtherNetworkWithoutPublicPort_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(new DockerService("id", "my-app", "image:latest", "latest",
                List.of(new PortMapping(3001, null, "tcp", "0.0.0.0")),
                List.of("some-other-network"), "running")));

        assertThat(refreshThenGetUnpublished(List.of())).isEmpty();
    }

    @Test
    void getUnpublishedVaierServerServices_crossNetworkContainerAlreadyPublished_excluded() {
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(new DockerService("id", "uptime-kuma", "image:latest", "latest",
                List.of(new PortMapping(3001, 3001, "tcp", "0.0.0.0")),
                List.of("uptime-kuma_default"), "running")));
        List<ReverseProxyRoute> existingRoutes = List.of(route(GATEWAY_IP, 3001));

        assertThat(refreshThenGetUnpublished(existingRoutes)).isEmpty();
    }

    // --- helpers ---

    private VpnClient client(String allowedIps) {
        String recentHandshake = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        return new VpnClient("pubkey", allowedIps, "1.2.3.4", "51820", recentHandshake, "0", "0");
    }

    private VpnClient disconnectedClient(String allowedIps) {
        return new VpnClient("pubkey", allowedIps, "1.2.3.4", "51820", "0", "0", "0");
    }

    private PeerConfiguration peerConfig(String name, String ip, MachineType type) {
        return new PeerConfiguration(name, ip, "", type, null);
    }

    // --- discoverAllLanServerContainers (#177, #184) ---

    @Test
    void discoverAllLanServerContainers_emptyWhenNoServersRegistered() {
        when(forGettingLanServers.getAll()).thenReturn(List.of());

        assertThat(service.discoverAllLanServerContainers()).isEmpty();
    }

    @Test
    void discoverAllLanServerContainers_relayResolved_scrapesDockerSocket() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5")
        ));
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("tcp://192.168.3.50:2375"))
        )).thenReturn(List.of(dockerService("plex", 32400)));

        var results = service.discoverAllLanServerContainers();

        assertThat(results).hasSize(1);
        var hostContainers = results.get(0);
        assertThat(hostContainers.name()).isEqualTo("nas");
        assertThat(hostContainers.lanAddress()).isEqualTo("192.168.3.50");
        assertThat(hostContainers.dockerPort()).isEqualTo(2375);
        assertThat(hostContainers.relayPeerName()).isEqualTo("apalveien5");
        assertThat(hostContainers.status()).isEqualTo("OK");
        assertThat(hostContainers.containers()).hasSize(1);
    }

    @Test
    void discoverAllLanServerContainers_serverAnchored_scrapesDirectly() {
        // A LAN server in the Vaier server's own subnet is anchored at "Vaier server" — the
        // scrape connects straight from the Vaier container, no relay hop.
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("vpc-box", "172.31.5.20", true, 2375), "Vaier server")
        ));
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("tcp://172.31.5.20:2375"))
        )).thenReturn(List.of(dockerService("plex", 32400)));

        var results = service.discoverAllLanServerContainers();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("vpc-box");
        assertThat(results.get(0).relayPeerName()).isEqualTo("Vaier server");
        assertThat(results.get(0).status()).isEqualTo("OK");
        assertThat(results.get(0).containers()).hasSize(1);
    }

    @Test
    void discoverAllLanServerContainers_relayUnknown_marksUnreachableAndDoesNotScrape() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), null)
        ));

        var results = service.discoverAllLanServerContainers();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("UNREACHABLE");
        assertThat(results.get(0).containers()).isEmpty();
        verify(forGettingServerInfo, never()).getServicesWithExposedPorts(any());
    }

    @Test
    void discoverAllLanServerContainers_dockerScrapeFails_marksUnreachable() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5")
        ));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenThrow(new RuntimeException("connection refused"));

        var results = service.discoverAllLanServerContainers();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("UNREACHABLE");
        assertThat(results.get(0).relayPeerName()).isEqualTo("apalveien5");
    }

    @Test
    void discoverAllLanServerContainers_skipsRunsDockerFalse() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "apalveien5"),
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5")
        ));
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("tcp://192.168.3.50:2375"))
        )).thenReturn(List.of(dockerService("plex", 32400)));

        var results = service.discoverAllLanServerContainers();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("nas");
    }

    @Test
    void discoverLanServerContainersForHost_runsDockerFalse_throws() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "apalveien5")
        ));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.discoverLanServerContainersForHost("printer"));
    }

    @Test
    void discoverLanServerContainersForHost_unknownName_throws() {
        when(forGettingLanServers.getAll()).thenReturn(List.of());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.discoverLanServerContainersForHost("ghost"));
    }

    @Test
    void discoverLanServerContainersForHost_runsDockerTrue_returnsContainers() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5")
        ));
        when(forGettingServerInfo.getServicesWithExposedPorts(
            argThat(s -> s.dockerHostUrl().equals("tcp://192.168.3.50:2375"))
        )).thenReturn(List.of(dockerService("plex", 32400)));

        var result = service.discoverLanServerContainersForHost("nas");

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.containers()).hasSize(1);
    }

    private DockerService dockerService(String name, int port) {
        return new DockerService("id123", name, "image:latest", "latest",
            List.of(new PortMapping(port, port, "tcp", "0.0.0.0")), List.of(), "running");
    }

    private DockerService wireguardContainer(String image) {
        return new DockerService("wg-id", "wireguard-client", image, "",
            List.of(), List.of(), "running");
    }

    private DockerService localContainer(String name, int port, String type) {
        return new DockerService("id", name, "image:latest", "latest",
            List.of(new PortMapping(port, null, type, "0.0.0.0")),
            List.of(VAIER_NETWORK), "running");
    }

    private ReverseProxyRoute route(String address, int port) {
        return new ReverseProxyRoute("route", "app.example.com", address, port, "svc", null);
    }

    /**
     * getUnpublishedVaierServerServices reads ContainerService's cached snapshot, so a test
     * must refresh() first to populate it from the stubbed Vaier-server Docker scrape.
     */
    private List<PublishableService> refreshThenGetUnpublished(List<ReverseProxyRoute> routes) {
        service.refresh();
        return service.getUnpublishedVaierServerServices(routes);
    }
}
