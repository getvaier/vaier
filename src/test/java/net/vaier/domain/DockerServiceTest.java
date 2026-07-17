package net.vaier.domain;

import net.vaier.domain.DockerService.PortMapping;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerServiceTest {

    private static DockerService container(List<String> networks) {
        return new DockerService("id", "grafana", "grafana:latest", "v",
            List.of(new PortMapping(3000, 13000, "tcp", "0.0.0.0")), networks, "running");
    }

    @Test
    void digestFromRepoDigests_readsTheRegistryDigestOfTheMatchingRepository() {
        // A container can carry several repo digests when its image is tagged into more than one repository;
        // the one that matters is the one for the repository the container actually runs.
        String digest = DockerService.digestFromRepoDigests(
            List.of("other/image@sha256:aaa", "vaultwarden/server@sha256:bbb"), "vaultwarden/server:latest");

        assertThat(digest).isEqualTo("sha256:bbb");
    }

    @Test
    void digestFromRepoDigests_fallsBackToTheSoleDigestWhenNoRepositoryMatches() {
        String digest = DockerService.digestFromRepoDigests(
            List.of("vaultwarden/server@sha256:bbb"), "registry-1.docker.io/vaultwarden/server:latest");

        assertThat(digest).isEqualTo("sha256:bbb");
    }

    @Test
    void digestFromRepoDigests_isNullWhenTheImageWasNeverPulledFromARegistry() {
        // A locally-built image has no repo digest at all — unknown, never "up to date".
        assertThat(DockerService.digestFromRepoDigests(List.of(), "my-local-build:latest")).isNull();
        assertThat(DockerService.digestFromRepoDigests(null, "my-local-build:latest")).isNull();
    }

    @Test
    void updateAvailabilityDefaultsToUnknownOnAScrapedContainer() {
        assertThat(container(List.of("bridge")).updateAvailable()).isEqualTo(UpdateAvailability.UNKNOWN);
    }

    @Test
    void withUpdateAvailability_returnsACopyCarryingTheVerdictAndLeavesTheOriginalAlone() {
        DockerService scraped = container(List.of("bridge"));

        DockerService judged = scraped.withUpdateAvailability(UpdateAvailability.UPDATE_AVAILABLE);

        assertThat(judged.updateAvailable()).isEqualTo(UpdateAvailability.UPDATE_AVAILABLE);
        assertThat(judged.containerId()).isEqualTo(scraped.containerId());
        assertThat(judged.image()).isEqualTo(scraped.image());
        assertThat(scraped.updateAvailable()).isEqualTo(UpdateAvailability.UNKNOWN);
    }

    @Test
    void isOnNetwork_trueWhenAttachedToThatNetwork() {
        assertThat(container(List.of("vaier-network")).isOnNetwork("vaier-network")).isTrue();
        assertThat(container(List.of("bridge")).isOnNetwork("vaier-network")).isFalse();
    }

    @Test
    void isOnNetwork_trueWhenContainerReportsNoNetworks() {
        // No network info — assumed reachable by container name.
        assertThat(container(List.of()).isOnNetwork("vaier-network")).isTrue();
    }

    @Test
    void reachableEndpoint_onVaierNetwork_usesContainerNameAndPrivatePort() {
        DockerService c = container(List.of("vaier-network"));
        PortMapping port = c.ports().get(0);

        assertThat(c.reachableEndpoint(port, "vaier-network", "172.20.0.1"))
            .contains(new DockerService.ServiceEndpoint("grafana", 3000));
    }

    @Test
    void reachableEndpoint_offVaierNetworkWithPublicPort_usesGatewayAndPublicPort() {
        DockerService c = container(List.of("bridge"));
        PortMapping port = c.ports().get(0);

        assertThat(c.reachableEndpoint(port, "vaier-network", "172.20.0.1"))
            .contains(new DockerService.ServiceEndpoint("172.20.0.1", 13000));
    }

    @Test
    void reachableEndpoint_offVaierNetworkWithoutPublicPort_isUnreachable() {
        DockerService c = container(List.of("bridge"));
        PortMapping unpublished = new PortMapping(3000, null, "tcp", "");

        assertThat(c.reachableEndpoint(unpublished, "vaier-network", "172.20.0.1")).isEmpty();
    }
}
