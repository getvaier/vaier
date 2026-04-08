package net.vaier.application.service;

import net.vaier.application.DiscoverLocalContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase.PeerContainers;
import net.vaier.domain.ContainerUpdateStatus;
import net.vaier.domain.DockerService;
import net.vaier.domain.port.ForCheckingRegistryDigests;
import net.vaier.domain.port.ForGettingImageDigests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CheckContainerUpdatesServiceTest {

    DiscoverLocalContainersUseCase localContainers;
    DiscoverPeerContainersUseCase peerContainers;
    ForCheckingRegistryDigests registryDigests;
    ForGettingImageDigests imageDigests;
    CheckContainerUpdatesService service;

    @BeforeEach
    void setUp() {
        localContainers = mock(DiscoverLocalContainersUseCase.class);
        peerContainers = mock(DiscoverPeerContainersUseCase.class);
        registryDigests = mock(ForCheckingRegistryDigests.class);
        imageDigests = mock(ForGettingImageDigests.class);
        when(peerContainers.discoverAll()).thenReturn(List.of());
        service = new CheckContainerUpdatesService(localContainers, peerContainers, registryDigests, imageDigests);
    }

    @Test
    void checkAll_noContainers_returnsEmptyList() {
        when(localContainers.discover()).thenReturn(List.of());

        List<ContainerUpdateStatus> results = service.checkAll();

        assertThat(results).isEmpty();
    }

    @Test
    void checkAll_matchingDigests_noUpdateAvailable() {
        when(localContainers.discover()).thenReturn(List.of(
                dockerService("id1", "nginx:1.25", "1.25")));
        when(imageDigests.getImageDigest(any(), eq("nginx:1.25")))
                .thenReturn(Optional.of("sha256:abc123"));
        when(registryDigests.getRemoteDigest("nginx", "1.25"))
                .thenReturn(Optional.of("sha256:abc123"));

        List<ContainerUpdateStatus> results = service.checkAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).updateAvailable()).isFalse();
        assertThat(results.get(0).image()).isEqualTo("nginx");
        assertThat(results.get(0).tag()).isEqualTo("1.25");
    }

    @Test
    void checkAll_differentDigests_updateAvailable() {
        when(localContainers.discover()).thenReturn(List.of(
                dockerService("id1", "nginx:1.25", "1.25")));
        when(imageDigests.getImageDigest(any(), eq("nginx:1.25")))
                .thenReturn(Optional.of("sha256:abc123"));
        when(registryDigests.getRemoteDigest("nginx", "1.25"))
                .thenReturn(Optional.of("sha256:def456"));

        List<ContainerUpdateStatus> results = service.checkAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).updateAvailable()).isTrue();
    }

    @Test
    void checkAll_latestTag_setsLatestTagFlag() {
        when(localContainers.discover()).thenReturn(List.of(
                dockerService("id1", "nginx:latest", "latest")));
        when(imageDigests.getImageDigest(any(), eq("nginx:latest")))
                .thenReturn(Optional.of("sha256:abc123"));
        when(registryDigests.getRemoteDigest("nginx", "latest"))
                .thenReturn(Optional.of("sha256:def456"));

        List<ContainerUpdateStatus> results = service.checkAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).latestTag()).isTrue();
    }

    @Test
    void checkAll_nonLatestTag_latestTagFlagIsFalse() {
        when(localContainers.discover()).thenReturn(List.of(
                dockerService("id1", "nginx:1.25", "1.25")));
        when(imageDigests.getImageDigest(any(), eq("nginx:1.25")))
                .thenReturn(Optional.of("sha256:abc123"));
        when(registryDigests.getRemoteDigest("nginx", "1.25"))
                .thenReturn(Optional.of("sha256:abc123"));

        List<ContainerUpdateStatus> results = service.checkAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).latestTag()).isFalse();
    }

    @Test
    void checkAll_deduplicatesSameImageTag() {
        when(localContainers.discover()).thenReturn(List.of(
                dockerService("id1", "nginx:1.25", "1.25"),
                dockerService("id2", "nginx:1.25", "1.25")));
        when(imageDigests.getImageDigest(any(), eq("nginx:1.25")))
                .thenReturn(Optional.of("sha256:abc123"));
        when(registryDigests.getRemoteDigest("nginx", "1.25"))
                .thenReturn(Optional.of("sha256:abc123"));

        List<ContainerUpdateStatus> results = service.checkAll();

        assertThat(results).hasSize(1);
        verify(registryDigests, times(1)).getRemoteDigest("nginx", "1.25");
    }

    @Test
    void checkAll_registryReturnsEmpty_skipsContainer() {
        when(localContainers.discover()).thenReturn(List.of(
                dockerService("id1", "myregistry.io/app:1.0", "1.0")));
        when(imageDigests.getImageDigest(any(), eq("myregistry.io/app:1.0")))
                .thenReturn(Optional.of("sha256:abc123"));
        when(registryDigests.getRemoteDigest("myregistry.io/app", "1.0"))
                .thenReturn(Optional.empty());

        List<ContainerUpdateStatus> results = service.checkAll();

        assertThat(results).isEmpty();
    }

    @Test
    void checkAll_localDigestEmpty_skipsContainer() {
        when(localContainers.discover()).thenReturn(List.of(
                dockerService("id1", "nginx:1.25", "1.25")));
        when(imageDigests.getImageDigest(any(), eq("nginx:1.25")))
                .thenReturn(Optional.empty());

        List<ContainerUpdateStatus> results = service.checkAll();

        assertThat(results).isEmpty();
        verifyNoInteractions(registryDigests);
    }

    @Test
    void getCachedResults_returnsCachedResultsFromCheckAll() {
        when(localContainers.discover()).thenReturn(List.of(
                dockerService("id1", "nginx:1.25", "1.25")));
        when(imageDigests.getImageDigest(any(), eq("nginx:1.25")))
                .thenReturn(Optional.of("sha256:abc123"));
        when(registryDigests.getRemoteDigest("nginx", "1.25"))
                .thenReturn(Optional.of("sha256:def456"));

        service.checkAll();
        List<ContainerUpdateStatus> cached = service.getCachedResults();

        assertThat(cached).hasSize(1);
        assertThat(cached.get(0).updateAvailable()).isTrue();
    }

    @Test
    void getCachedResults_beforeAnyCheck_returnsEmptyList() {
        assertThat(service.getCachedResults()).isEmpty();
    }

    @Test
    void checkAll_includesPeerContainers() {
        when(localContainers.discover()).thenReturn(List.of());
        when(peerContainers.discoverAll()).thenReturn(List.of(
                new PeerContainers("server1", "10.0.0.2", "OK",
                        List.of(dockerService("id1", "redis:7.2", "7.2")))));
        when(imageDigests.getImageDigest(any(), eq("redis:7.2")))
                .thenReturn(Optional.of("sha256:aaa"));
        when(registryDigests.getRemoteDigest("redis", "7.2"))
                .thenReturn(Optional.of("sha256:bbb"));

        List<ContainerUpdateStatus> results = service.checkAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).image()).isEqualTo("redis");
        assertThat(results.get(0).updateAvailable()).isTrue();
    }

    @Test
    void checkAll_skipsUnreachablePeerContainers() {
        when(localContainers.discover()).thenReturn(List.of());
        when(peerContainers.discoverAll()).thenReturn(List.of(
                new PeerContainers("server1", "10.0.0.2", "UNREACHABLE", List.of())));

        List<ContainerUpdateStatus> results = service.checkAll();

        assertThat(results).isEmpty();
    }

    @Test
    void checkAll_whenDiscoveryFails_doesNotThrow() {
        when(localContainers.discover()).thenThrow(new RuntimeException("docker down"));

        assertThatCode(() -> service.checkAll()).doesNotThrowAnyException();
    }

    @Test
    void checkAll_imageWithNoTag_defaultsToLatest() {
        when(localContainers.discover()).thenReturn(List.of(
                dockerService("id1", "nginx", "latest")));
        when(imageDigests.getImageDigest(any(), eq("nginx")))
                .thenReturn(Optional.of("sha256:abc123"));
        when(registryDigests.getRemoteDigest("nginx", "latest"))
                .thenReturn(Optional.of("sha256:def456"));

        List<ContainerUpdateStatus> results = service.checkAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).tag()).isEqualTo("latest");
        assertThat(results.get(0).latestTag()).isTrue();
    }

    @Test
    void checkAll_latestTagWithResolvedVersion_usesResolvedVersion() {
        when(localContainers.discover()).thenReturn(List.of(
                dockerService("id1", "traefik:latest", "v3.6.12")));
        when(imageDigests.getImageDigest(any(), eq("traefik:latest")))
                .thenReturn(Optional.of("sha256:abc123"));
        when(registryDigests.getRemoteDigest("traefik", "v3.6.12"))
                .thenReturn(Optional.of("sha256:abc123"));

        List<ContainerUpdateStatus> results = service.checkAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).tag()).isEqualTo("v3.6.12");
        assertThat(results.get(0).latestTag()).isFalse();
        assertThat(results.get(0).updateAvailable()).isFalse();
        verify(registryDigests).getRemoteDigest("traefik", "v3.6.12");
        verify(registryDigests, never()).getRemoteDigest("traefik", "latest");
    }

    @Test
    void checkAll_latestTagWithNoResolvedVersion_usesLatest() {
        when(localContainers.discover()).thenReturn(List.of(
                dockerService("id1", "myapp:latest", "latest")));
        when(imageDigests.getImageDigest(any(), eq("myapp:latest")))
                .thenReturn(Optional.of("sha256:abc123"));
        when(registryDigests.getRemoteDigest("myapp", "latest"))
                .thenReturn(Optional.of("sha256:def456"));

        List<ContainerUpdateStatus> results = service.checkAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).tag()).isEqualTo("latest");
        assertThat(results.get(0).latestTag()).isTrue();
    }

    @Test
    void parseImageAndTag_withTag() {
        String[] result = CheckContainerUpdatesService.parseImageAndTag("nginx:1.25");
        assertThat(result).containsExactly("nginx", "1.25");
    }

    @Test
    void parseImageAndTag_withoutTag() {
        String[] result = CheckContainerUpdatesService.parseImageAndTag("nginx");
        assertThat(result).containsExactly("nginx", "latest");
    }

    @Test
    void parseImageAndTag_withRegistry() {
        String[] result = CheckContainerUpdatesService.parseImageAndTag("lscr.io/linuxserver/wireguard:latest");
        assertThat(result).containsExactly("lscr.io/linuxserver/wireguard", "latest");
    }

    private DockerService dockerService(String id, String image, String version) {
        return new DockerService(id, image.replace(":", "-").replace("/", "-") + "-container",
                image, version,
                List.of(new DockerService.PortMapping(80, 80, "tcp", "0.0.0.0")), List.of());
    }
}
