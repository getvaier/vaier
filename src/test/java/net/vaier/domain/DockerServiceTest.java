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
    void digestsFromRepoDigests_keepsEveryDigestOfTheRepository_andOnlyThose() {
        assertThat(DockerService.digestsFromRepoDigests(
            List.of("phpmyadmin@sha256:old", "other/image@sha256:aaa", "phpmyadmin@sha256:new"), "phpmyadmin"))
            .containsExactly("sha256:old", "sha256:new");
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
    void withUpdateAvailability_keepsHowTheContainerWasStartedAndHowItWasJudged() {
        // The two verdicts are settled at different moments by different things — the update sweep and the
        // machine scrape — so each copy must carry the other's answer forward untouched.
        DockerService judged = DockerService.builder()
            .containerId("id").containerName("vaultwarden").image("vaultwarden/server:latest").version("v")
            .ports(List.of(new PortMapping(80, 8080, "tcp", "0.0.0.0")))
            .networks(List.of("bridge")).state("running")
            .composeCoordinates(new ComposeCoordinates("apps", "vaultwarden",
                List.of("/srv/apps/docker-compose.yml"), "/srv/apps"))
            .updateEligibility(ContainerUpdateEligibility.UPDATABLE)
            .build();

        DockerService swept = judged.withUpdateAvailability(UpdateAvailability.UPDATE_AVAILABLE);

        assertThat(swept.composeCoordinates()).isEqualTo(judged.composeCoordinates());
        assertThat(swept.updateEligibility()).isEqualTo(ContainerUpdateEligibility.UPDATABLE);
        assertThat(swept.updateAvailable()).isEqualTo(UpdateAvailability.UPDATE_AVAILABLE);
    }

    @Test
    void aScrapedContainerCarriesNoCoordinatesAndNoEligibilityUntilSomethingSuppliesThem() {
        assertThat(container(List.of("bridge")).composeCoordinates()).isNull();
        assertThat(container(List.of("bridge")).updateEligibility()).isNull();
    }

    @Test
    void isOnNetwork_trueWhenAttachedToThatNetwork() {
        assertThat(container(List.of("vaier-network")).isOnNetwork("vaier-network")).isTrue();
        assertThat(container(List.of("bridge")).isOnNetwork("vaier-network")).isFalse();
    }

    @Test
    void isOnNetwork_seesThroughDockerComposesProjectPrefix() {
        // Docker names a compose network `<project>_<name>`, so the network Vaier's own stack declares as
        // `vaier-network` is reported by the daemon as `vaier_vaier-network`. Matching the bare name only
        // meant every container in the stack read as off-network — and a container off the network is
        // reachable only if it publishes a host port, which is how Traefik's dashboard (8080, published
        // nowhere) stayed invisible to publishing no matter what the catalogue offered.
        assertThat(container(List.of("vaier_vaier-network")).isOnNetwork("vaier-network")).isTrue();
        assertThat(container(List.of("staging_vaier-network")).isOnNetwork("vaier-network")).isTrue();

        // The suffix must be a whole segment, not any name that happens to end in the same letters.
        assertThat(container(List.of("not-vaier-network")).isOnNetwork("vaier-network")).isFalse();
        assertThat(container(List.of("vaier-network-external")).isOnNetwork("vaier-network")).isFalse();
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

    @Test
    void everyReachableEndpoint_ofAnOnNetworkContainerThatAlsoPublishesAPort_listsBothSpellings() {
        // The same port of the same container can be written two ways, and a route published before Vaier
        // could see the container on its own network holds the gateway spelling. Asking "is this already
        // published?" of only the preferred spelling would offer a published service all over again.
        DockerService c = container(List.of("vaier_vaier-network"));
        PortMapping port = c.ports().get(0);

        assertThat(c.everyReachableEndpoint(port, "vaier-network", "172.20.0.1")).containsExactly(
            new DockerService.ServiceEndpoint("grafana", 3000),
            new DockerService.ServiceEndpoint("172.20.0.1", 13000));
    }

    @Test
    void everyReachableEndpoint_leadsWithThePreferredSpelling() {
        // Whatever else it lists, the first entry is what reachableEndpoint would pick — the endpoint a
        // new route is actually written with.
        DockerService c = container(List.of("vaier_vaier-network"));
        PortMapping port = c.ports().get(0);

        assertThat(c.everyReachableEndpoint(port, "vaier-network", "172.20.0.1").get(0))
            .isEqualTo(c.reachableEndpoint(port, "vaier-network", "172.20.0.1").orElseThrow());
    }

    @Test
    void everyReachableEndpoint_offNetwork_isJustTheGatewaySpelling() {
        DockerService c = container(List.of("bridge"));

        assertThat(c.everyReachableEndpoint(c.ports().get(0), "vaier-network", "172.20.0.1"))
            .containsExactly(new DockerService.ServiceEndpoint("172.20.0.1", 13000));
        assertThat(c.everyReachableEndpoint(new PortMapping(3000, null, "tcp", ""), "vaier-network", "172.20.0.1"))
            .isEmpty();
    }
}
