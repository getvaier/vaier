package net.vaier.domain;

import org.junit.jupiter.api.Test;

import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VaierServerCatalogueTest {

    @Test
    void isExcluded_hidesTheContainersOfVaiersOwnStack() {
        // The edge, the tunnel and Vaier itself.
        assertThat(VaierServerCatalogue.isExcluded("vaier")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("wireguard")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("wireguard-masquerade")).isTrue();

        // The social-login chain — already published at oauth2.<domain> and dex.<domain>.
        assertThat(VaierServerCatalogue.isExcluded("oauth2-proxy")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("dex")).isTrue();

        // The edge's threat detection.
        assertThat(VaierServerCatalogue.isExcluded("crowdsec")).isTrue();

        // The offline placeholder and the log rotator.
        assertThat(VaierServerCatalogue.isExcluded("vaier-offline")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("traefik-logrotate")).isTrue();

        // The LAN-route sidecars.
        assertThat(VaierServerCatalogue.isExcluded("host-lan-routes")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("traefik-lan-routes")).isTrue();

        // The one-shot init sidecars.
        assertThat(VaierServerCatalogue.isExcluded("vaier-init")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("oauth2-proxy-init")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("dex-init")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("geoip-init")).isTrue();
    }

    @Test
    void isExcluded_hidesTheDockerSocketProxy() {
        // The sharpest one: docker-proxy exposes 2375, the unauthenticated Docker API. Offering it as a
        // publishable service invites an operator to put root on every container behind a public hostname.
        assertThat(VaierServerCatalogue.isExcluded("docker-proxy")).isTrue();
    }

    @Test
    void isExcluded_stillHidesTheDecommissionedAutheliaStack() {
        // Removed from the stack in #305, but an updated host can still be carrying the containers.
        assertThat(VaierServerCatalogue.isExcluded("authelia")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("redis")).isTrue();
    }

    @Test
    void isExcluded_isCaseInsensitiveAndFalseForOrdinaryContainers() {
        assertThat(VaierServerCatalogue.isExcluded("WireGuard")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("grafana")).isFalse();
        // The operator's own containers on the Vaier host stay offered.
        assertThat(VaierServerCatalogue.isExcluded("pihole")).isFalse();
    }

    @Test
    void isVaierOwnStack_countsTheOfferedCarveOutAsVaiersOwnToo() {
        // Offered is not the opposite of Vaier's own: Traefik is offered for publishing and is still part
        // of the stack a Vaier release pins. Asking isExcluded alone would call it the operator's.
        assertThat(VaierServerCatalogue.isVaierOwnStack("traefik")).isTrue();
        assertThat(VaierServerCatalogue.isVaierOwnStack("vaier")).isTrue();
        assertThat(VaierServerCatalogue.isVaierOwnStack("docker-proxy")).isTrue();
        assertThat(VaierServerCatalogue.isVaierOwnStack("pihole")).isFalse();
        assertThat(VaierServerCatalogue.isVaierOwnStack("Traefik")).isTrue();
    }

    @Test
    void isOffered_isTheCarveOutForVaiersOwnContainersThatAreWorthPublishing() {
        assertThat(VaierServerCatalogue.isOffered("traefik")).isTrue();
        assertThat(VaierServerCatalogue.isOffered("crowdsec")).isFalse();
        // Not a claim about the operator's containers — those are offered because they aren't excluded.
        assertThat(VaierServerCatalogue.isOffered("grafana")).isFalse();
    }

    @Test
    void isPublishablePort_restrictsKnownServicesToTheirListedPorts() {
        assertThat(VaierServerCatalogue.isPublishablePort("traefik", 8080)).isTrue();
        assertThat(VaierServerCatalogue.isPublishablePort("traefik", 80)).isFalse();
        assertThat(VaierServerCatalogue.isPublishablePort("traefik", 443)).isFalse();
    }

    @Test
    void isPublishablePort_allowsEveryPortOfAnUnknownContainer() {
        assertThat(VaierServerCatalogue.isPublishablePort("grafana", 3000)).isTrue();
    }

    @Test
    void rootRedirectPath_returnsTheKnownPathOrNull() {
        assertThat(VaierServerCatalogue.rootRedirectPath("traefik")).isEqualTo("/dashboard/");
        assertThat(VaierServerCatalogue.rootRedirectPath("grafana")).isNull();
    }

    /**
     * The catalogue's blind spot has always been drift: it was written against a stack that had Authelia
     * and Redis in it and was never revisited when oauth2-proxy, Dex, CrowdSec, the socket proxy and the
     * offline page arrived — so every one of those was quietly on offer. A hand-maintained list of a
     * file's contents goes stale silently, so bind it to the file: every container Vaier's own compose
     * stack starts must be classified here, either hidden or deliberately offered. Adding a service to
     * docker-compose.yml without deciding which it is now fails the build.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyContainerInVaiersOwnStackIsEitherHiddenOrDeliberatelyOffered() throws Exception {
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");

        List<String> unclassified = new ArrayList<>();
        for (Object definition : services.values()) {
            Object containerName = ((Map<String, Object>) definition).get("container_name");
            if (containerName == null) continue;
            String name = containerName.toString();
            if (!VaierServerCatalogue.isExcluded(name) && !VaierServerCatalogue.isOffered(name)) {
                unclassified.add(name);
            }
        }

        assertThat(unclassified)
            .as("containers of Vaier's own stack that the catalogue neither hides nor offers")
            .isEmpty();
    }

    // --- what the update sweep may ask about (#353) ---

    private static DockerService running(String name, String image) {
        return new DockerService("id-" + name, name, image, "v",
            List.of(), List.of(), "running", "sha256:local", UpdateAvailability.UNKNOWN);
    }

    @Test
    void vaiersOwnStackIsNotSweptAtAll_soNoRegistryIsAskedAboutAnImageNobodyCanActOn() {
        // The images move with a Vaier release, so the only honest resolution of a mark on one is "wait for
        // a Vaier release" — an alert an operator cannot act on teaches them to filter the channel. Dropped
        // BEFORE the registries are asked, not swept-and-hidden: it should not spend the rate limit either.
        List<DockerService> vaierServerContainers = List.of(
            running("traefik", "traefik:v3.6.14"),
            running("wireguard", "linuxserver/wireguard:latest"),
            running("pihole", "pihole/pihole:latest"));

        assertThat(VaierServerCatalogue.sweepable(vaierServerContainers))
            .extracting(DockerService::containerName)
            .containsExactly("pihole");
    }

    @Test
    void vaierItselfIsNotSweptEither_becauseSettingsSpeaksForIt() {
        // The plan of record kept `vaier` because Settings has a real button. The operator overruled it:
        // Settings asks for itself, and on a box that builds Vaier locally the local digest differs from
        // Hub's `latest`, so the mark would read "newer available" when acting on it would DOWNGRADE.
        // A mark that talks you into a downgrade is worse than no mark, so there is no exception at all.
        assertThat(VaierServerCatalogue.sweepable(List.of(running("vaier", "getvaier/vaier:latest"))))
            .isEmpty();
    }

    @Test
    void theOfferedHalfOfTheStackIsDroppedToo_notJustTheHiddenHalf() {
        // Traefik is OFFERED for publishing and is still part of the stack a Vaier release pins — which is
        // exactly the container this issue was filed about. Offered is not the opposite of Vaier's own.
        assertThat(VaierServerCatalogue.isOffered("traefik")).isTrue();
        assertThat(VaierServerCatalogue.sweepable(List.of(running("traefik", "traefik:v3.6.14")))).isEmpty();
    }

    @Test
    void sweepableIsNullSafeAndKeepsEverythingItDoesNotRecognise() {
        assertThat(VaierServerCatalogue.sweepable(null)).isEmpty();
        assertThat(VaierServerCatalogue.sweepable(List.of(running("openhab", "openhab:latest"))))
            .hasSize(1);
    }
}
