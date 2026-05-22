package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServerTest {

    @Test
    void localDockerHostUrl_usesDockerHostEnvWhenSet() {
        assertThat(Server.localDockerHostUrl("tcp://10.0.0.5:2375", "Linux"))
            .isEqualTo("tcp://10.0.0.5:2375");
    }

    @Test
    void localDockerHostUrl_envWins_evenOnWindows() {
        assertThat(Server.localDockerHostUrl("tcp://10.0.0.5:2375", "Windows 11"))
            .isEqualTo("tcp://10.0.0.5:2375");
    }

    @Test
    void localDockerHostUrl_defaultsToUnixSocketOnLinux() {
        assertThat(Server.localDockerHostUrl(null, "Linux"))
            .isEqualTo("unix:///var/run/docker.sock");
    }

    @Test
    void localDockerHostUrl_defaultsToUnixSocketOnMac() {
        assertThat(Server.localDockerHostUrl(null, "Mac OS X"))
            .isEqualTo("unix:///var/run/docker.sock");
    }

    @Test
    void localDockerHostUrl_defaultsToNamedPipeOnWindows() {
        assertThat(Server.localDockerHostUrl(null, "Windows 11"))
            .isEqualTo("npipe:////./pipe/docker_engine");
    }

    @Test
    void localDockerHostUrl_blankEnvFallsBackToDefault() {
        assertThat(Server.localDockerHostUrl("   ", "Linux"))
            .isEqualTo("unix:///var/run/docker.sock");
    }
}
