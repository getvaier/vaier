package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ImageReferenceTest {

    @Test
    void parse_extractsRegistryRepositoryAndTagFromVariousImageForms() {
        record Row(String description, String image, String registry, String repository, String tag) {}
        List<Row> rows = List.of(
            new Row("single-segment Docker Hub image gets the library prefix",
                "redis:7.2", "registry-1.docker.io", "library/redis", "7.2"),
            new Row("two-segment Docker Hub image keeps its namespace",
                "vaultwarden/server:latest", "registry-1.docker.io", "vaultwarden/server", "latest"),
            new Row("lscr.io image keeps its registry and nested repository",
                "lscr.io/linuxserver/wireguard:1.0.20250521-r1-ls110", "lscr.io", "linuxserver/wireguard", "1.0.20250521-r1-ls110"),
            new Row("ghcr.io image with a deeply nested repository",
                "ghcr.io/home-assistant/home-assistant:2025.7", "ghcr.io", "home-assistant/home-assistant", "2025.7"),
            // "linuxserver/wireguard" — no dot, no colon, not localhost: a Docker Hub namespace, not a registry.
            new Row("first segment is treated as a registry only when it looks like a host",
                "linuxserver/wireguard:latest", "registry-1.docker.io", "linuxserver/wireguard", "latest"),
            new Row("registry with an explicit port",
                "localhost:5000/my/app:dev", "localhost:5000", "my/app", "dev"),
            new Row("a portless registry's tag colon is not mistaken for a registry port",
                "quay.io/prometheus/node-exporter:v1.8.2", "quay.io", "prometheus/node-exporter", "v1.8.2")
        );

        for (Row row : rows) {
            ImageReference ref = ImageReference.parse(row.image()).orElseThrow();

            assertThat(ref.registry()).as(row.description() + " (registry)").isEqualTo(row.registry());
            assertThat(ref.repository()).as(row.description() + " (repository)").isEqualTo(row.repository());
            assertThat(ref.tag()).as(row.description() + " (tag)").isEqualTo(row.tag());
        }
    }

    @Test
    void defaultsMissingTagToLatest() {
        ImageReference ref = ImageReference.parse("vaultwarden/server").orElseThrow();

        assertThat(ref.repository()).isEqualTo("vaultwarden/server");
        assertThat(ref.tag()).isEqualTo("latest");
    }

    @Test
    void rejects_returnsEmptyForUnparsableOrNonTaggedImages() {
        record Row(String description, String image) {}
        List<Row> rows = List.of(
            // A container pinned to an immutable digest has no tag to re-resolve — it is not "out of
            // date", it is exactly what was asked for.
            new Row("image pinned by digest, because it can never drift", "vaultwarden/server@sha256:abc123"),
            new Row("null image", null),
            new Row("blank image", "   "),
            // Docker reports a bare sha256 id for containers whose image tag was removed.
            new Row("an image id rather than a name", "sha256:9f2c1b3d4e5f")
        );

        for (Row row : rows) {
            assertThat(ImageReference.parse(row.image())).as(row.description()).isEmpty();
        }
    }

    @Test
    void canonicalFormRoundTripsRegistryRepositoryAndTag() {
        Optional<ImageReference> ref = ImageReference.parse("vaultwarden/server:latest");

        assertThat(ref.orElseThrow().canonical()).isEqualTo("registry-1.docker.io/vaultwarden/server:latest");
    }
}
