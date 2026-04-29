package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanServerTest {

    @Test
    void validate_runsDockerTrueWithValidPort_passes() {
        LanServer.validate("nas", "192.168.3.50", true, 2375);
    }

    @Test
    void validate_runsDockerFalseWithoutPort_passes() {
        LanServer.validate("printer", "192.168.3.20", false, null);
    }

    @Test
    void validate_runsDockerFalseWithPort_passes() {
        LanServer.validate("printer", "192.168.3.20", false, 9100);
    }

    @Test
    void validate_runsDockerTrueWithoutPort_throws() {
        assertThatThrownBy(() -> LanServer.validate("nas", "192.168.3.50", true, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dockerPort");
    }

    @Test
    void validate_runsDockerTrueWithPortBelowOne_throws() {
        assertThatThrownBy(() -> LanServer.validate("nas", "192.168.3.50", true, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dockerPort");
    }

    @Test
    void validate_runsDockerTrueWithPortAbove65535_throws() {
        assertThatThrownBy(() -> LanServer.validate("nas", "192.168.3.50", true, 70000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dockerPort");
    }

    @Test
    void validate_blankName_throws() {
        assertThatThrownBy(() -> LanServer.validate("  ", "192.168.3.50", true, 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void validate_nullName_throws() {
        assertThatThrownBy(() -> LanServer.validate(null, "192.168.3.50", true, 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void validate_blankLanAddress_throws() {
        assertThatThrownBy(() -> LanServer.validate("nas", "", true, 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanAddress");
    }

    @Test
    void validate_nonIpv4LanAddress_throws() {
        assertThatThrownBy(() -> LanServer.validate("nas", "not-an-ip", true, 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanAddress");
    }

    @Test
    void validate_runsDockerFalse_lanAddressStillValidated() {
        assertThatThrownBy(() -> LanServer.validate("printer", "not-an-ip", false, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanAddress");
    }

    @Test
    void record_fieldsAreAccessible() {
        LanServer server = new LanServer("nas", "192.168.3.50", true, 2375);

        assertThat(server.name()).isEqualTo("nas");
        assertThat(server.lanAddress()).isEqualTo("192.168.3.50");
        assertThat(server.runsDocker()).isTrue();
        assertThat(server.dockerPort()).isEqualTo(2375);
    }

    @Test
    void record_runsDockerFalse_dockerPortMayBeNull() {
        LanServer server = new LanServer("printer", "192.168.3.20", false, null);

        assertThat(server.name()).isEqualTo("printer");
        assertThat(server.lanAddress()).isEqualTo("192.168.3.20");
        assertThat(server.runsDocker()).isFalse();
        assertThat(server.dockerPort()).isNull();
    }
}
