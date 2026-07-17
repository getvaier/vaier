package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ImageReferenceTest {

    @Test
    void parsesSingleSegmentDockerHubImageWithLibraryPrefix() {
        ImageReference ref = ImageReference.parse("redis:7.2").orElseThrow();

        assertThat(ref.registry()).isEqualTo("registry-1.docker.io");
        assertThat(ref.repository()).isEqualTo("library/redis");
        assertThat(ref.tag()).isEqualTo("7.2");
    }

    @Test
    void defaultsMissingTagToLatest() {
        ImageReference ref = ImageReference.parse("vaultwarden/server").orElseThrow();

        assertThat(ref.repository()).isEqualTo("vaultwarden/server");
        assertThat(ref.tag()).isEqualTo("latest");
    }

    @Test
    void parsesTwoSegmentDockerHubImageWithoutLibraryPrefix() {
        ImageReference ref = ImageReference.parse("vaultwarden/server:latest").orElseThrow();

        assertThat(ref.registry()).isEqualTo("registry-1.docker.io");
        assertThat(ref.repository()).isEqualTo("vaultwarden/server");
        assertThat(ref.tag()).isEqualTo("latest");
    }

    @Test
    void parsesLscrImageKeepingItsRegistryAndNestedRepository() {
        ImageReference ref = ImageReference.parse("lscr.io/linuxserver/wireguard:1.0.20250521-r1-ls110")
            .orElseThrow();

        assertThat(ref.registry()).isEqualTo("lscr.io");
        assertThat(ref.repository()).isEqualTo("linuxserver/wireguard");
        assertThat(ref.tag()).isEqualTo("1.0.20250521-r1-ls110");
    }

    @Test
    void parsesGhcrImageWithDeeplyNestedRepository() {
        ImageReference ref = ImageReference.parse("ghcr.io/home-assistant/home-assistant:2025.7").orElseThrow();

        assertThat(ref.registry()).isEqualTo("ghcr.io");
        assertThat(ref.repository()).isEqualTo("home-assistant/home-assistant");
        assertThat(ref.tag()).isEqualTo("2025.7");
    }

    @Test
    void treatsFirstSegmentAsRegistryOnlyWhenItLooksLikeAHost() {
        // "linuxserver/wireguard" — no dot, no colon, not localhost: a Docker Hub namespace, not a registry.
        ImageReference ref = ImageReference.parse("linuxserver/wireguard:latest").orElseThrow();

        assertThat(ref.registry()).isEqualTo("registry-1.docker.io");
        assertThat(ref.repository()).isEqualTo("linuxserver/wireguard");
    }

    @Test
    void parsesRegistryWithExplicitPort() {
        ImageReference ref = ImageReference.parse("localhost:5000/my/app:dev").orElseThrow();

        assertThat(ref.registry()).isEqualTo("localhost:5000");
        assertThat(ref.repository()).isEqualTo("my/app");
        assertThat(ref.tag()).isEqualTo("dev");
    }

    @Test
    void doesNotMistakeAPortlessRegistryTagColonForARegistryPort() {
        ImageReference ref = ImageReference.parse("quay.io/prometheus/node-exporter:v1.8.2").orElseThrow();

        assertThat(ref.registry()).isEqualTo("quay.io");
        assertThat(ref.repository()).isEqualTo("prometheus/node-exporter");
        assertThat(ref.tag()).isEqualTo("v1.8.2");
    }

    @Test
    void rejectsAnImagePinnedByDigestBecauseItCanNeverDrift() {
        // A container pinned to an immutable digest has no tag to re-resolve — it is not "out of date",
        // it is exactly what was asked for.
        assertThat(ImageReference.parse("vaultwarden/server@sha256:abc123")).isEmpty();
    }

    @Test
    void rejectsBlankOrNullImages() {
        assertThat(ImageReference.parse(null)).isEmpty();
        assertThat(ImageReference.parse("   ")).isEmpty();
    }

    @Test
    void rejectsAnImageIdRatherThanAName() {
        // Docker reports a bare sha256 id for containers whose image tag was removed.
        assertThat(ImageReference.parse("sha256:9f2c1b3d4e5f")).isEmpty();
    }

    @Test
    void canonicalFormRoundTripsRegistryRepositoryAndTag() {
        Optional<ImageReference> ref = ImageReference.parse("vaultwarden/server:latest");

        assertThat(ref.orElseThrow().canonical()).isEqualTo("registry-1.docker.io/vaultwarden/server:latest");
    }
}
