package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WireguardClientImageTest {

    private static DockerService container(String image) {
        return new DockerService("id", "wireguard", image, "v", List.of(), List.of(), "running");
    }

    @Test
    void anyOutdated_trueWhenAWireguardImageDiffersFromExpected() {
        List<DockerService> containers = List.of(
            container("nginx:latest"),
            container("lscr.io/linuxserver/wireguard:1.0.20240000-old"));

        assertThat(WireguardClientImage.anyOutdated(containers)).isTrue();
    }

    @Test
    void anyOutdated_falseWhenEveryWireguardImageMatchesExpected() {
        List<DockerService> containers = List.of(
            container("nginx:latest"),
            container(WireguardClientImage.EXPECTED));

        assertThat(WireguardClientImage.anyOutdated(containers)).isFalse();
    }

    @Test
    void anyOutdated_falseWhenNoWireguardImagesPresent() {
        assertThat(WireguardClientImage.anyOutdated(List.of(container("nginx:latest")))).isFalse();
        assertThat(WireguardClientImage.anyOutdated(List.of())).isFalse();
    }
}
