package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DockerServiceVersionTest {

    @Test
    void returnsOciVersionLabel() {
        var labels = Map.of("org.opencontainers.image.version", "2.11.0");
        assertThat(DockerService.versionFromLabels(labels, "nginx:latest")).isEqualTo("2.11.0");
    }

    @Test
    void returnsLabelSchemaVersionWhenOciAbsent() {
        var labels = Map.of("org.label-schema.version", "1.5.3");
        assertThat(DockerService.versionFromLabels(labels, "app:latest")).isEqualTo("1.5.3");
    }

    @Test
    void extractsVersionFromLinuxServerBuildVersionLabel() {
        var labels = Map.of("build_version", "Version:- 1.0.20210914 Build-date:- 2021-09-14");
        assertThat(DockerService.versionFromLabels(labels, "lscr.io/linuxserver/wireguard:latest")).isEqualTo("1.0.20210914");
    }

    @Test
    void fallsBackToImageTagWhenNoLabels() {
        assertThat(DockerService.versionFromLabels(Map.of(), "nginx:1.25.3")).isEqualTo("1.25.3");
    }

    @Test
    void fallsBackToImageTagWhenLabelsNull() {
        assertThat(DockerService.versionFromLabels(null, "traefik:v3.1")).isEqualTo("v3.1");
    }

    @Test
    void returnsLatestWhenTagIsLatestAndNoLabels() {
        assertThat(DockerService.versionFromLabels(Map.of(), "nginx:latest")).isEqualTo("latest");
    }

    @Test
    void returnsLatestWhenNoTagAndNoLabels() {
        assertThat(DockerService.versionFromLabels(Map.of(), "nginx")).isEqualTo("latest");
    }

    @Test
    void ociLabelTakesPrecedenceOverBuildVersion() {
        var labels = Map.of(
            "org.opencontainers.image.version", "3.0.0",
            "build_version", "Version:- 1.0.20210914 Build-date:- 2021-09-14"
        );
        assertThat(DockerService.versionFromLabels(labels, "image:latest")).isEqualTo("3.0.0");
    }
}
