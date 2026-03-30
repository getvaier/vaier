package net.vaier.application.service;

import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase.PeerContainers;
import net.vaier.application.GetLocalDockerServicesUseCase;
import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.application.PublishPeerServiceUseCase.PublishableSource;
import net.vaier.domain.DockerService;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetPublishableServicesServiceTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Mock
    DiscoverPeerContainersUseCase discoverPeerContainersUseCase;

    @Mock
    GetLocalDockerServicesUseCase getLocalDockerServicesUseCase;

    @InjectMocks
    GetPublishableServicesService service;

    @Test
    void getPublishableServices_allPeersUnreachable_returnsOnlyLocalServices() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            unreachablePeer("alice", "10.13.13.2")
        ));
        PublishableService localSvc = localService("my-app", 3000);
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of(localSvc));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).containsExactly(localSvc);
    }

    @Test
    void getPublishableServices_peerWithTcpPortNotPublished_included() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(container("my-app", 8080, "tcp")))
        ));
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).address()).isEqualTo("10.13.13.2");
        assertThat(result.get(0).port()).isEqualTo(8080);
        assertThat(result.get(0).source()).isEqualTo(PublishableSource.PEER);
    }

    @Test
    void getPublishableServices_peerPortAlreadyPublished_excluded() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            route("10.13.13.2", 8080)
        ));
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(container("my-app", 8080, "tcp")))
        ));
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_peerWithUdpPort_excluded() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(container("dns-server", 53, "udp")))
        ));
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    @Test
    void getPublishableServices_mergesPeerAndLocalServices() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
            okPeer("alice", "10.13.13.2", List.of(container("peer-app", 9000, "tcp")))
        ));
        PublishableService localSvc = localService("local-app", 3000);
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of(localSvc));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PublishableService::source)
            .containsExactlyInAnyOrder(PublishableSource.PEER, PublishableSource.LOCAL);
    }

    @Test
    void getPublishableServices_duplicates_deduplicated() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of());
        PublishableService svc = localService("my-app", 3000);
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any()))
            .thenReturn(List.of(svc, svc));

        List<PublishableService> result = service.getPublishableServices();

        assertThat(result).hasSize(1);
    }

    @Test
    void getPublishableServices_noPeersNoLocal_returnsEmpty() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of());
        when(getLocalDockerServicesUseCase.getUnpublishedLocalServices(any())).thenReturn(List.of());

        assertThat(service.getPublishableServices()).isEmpty();
    }

    // --- helpers ---

    private PeerContainers unreachablePeer(String name, String ip) {
        return new PeerContainers(name, ip, "UNREACHABLE", List.of());
    }

    private PeerContainers okPeer(String name, String ip, List<DockerService> containers) {
        return new PeerContainers(name, ip, "OK", containers);
    }

    private DockerService container(String name, int port, String type) {
        return new DockerService("id", name, "image",
            List.of(new PortMapping(port, port, type, "0.0.0.0")));
    }

    private PublishableService localService(String name, int port) {
        return new PublishableService(PublishableSource.LOCAL, null, name, name, port, null);
    }

    private ReverseProxyRoute route(String address, int port) {
        return new ReverseProxyRoute("r", "svc.example.com", address, port, "svc", null);
    }
}
