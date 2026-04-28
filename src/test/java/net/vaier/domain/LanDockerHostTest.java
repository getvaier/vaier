package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanDockerHostTest {

    @Test
    void validate_validInputs_passes() {
        LanDockerHost.validate("nas", "192.168.3.50", 2375);
    }

    @Test
    void validate_blankName_throws() {
        assertThatThrownBy(() -> LanDockerHost.validate("  ", "192.168.3.50", 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void validate_nullName_throws() {
        assertThatThrownBy(() -> LanDockerHost.validate(null, "192.168.3.50", 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void validate_blankIp_throws() {
        assertThatThrownBy(() -> LanDockerHost.validate("nas", "", 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hostIp");
    }

    @Test
    void validate_nonIpv4String_throws() {
        assertThatThrownBy(() -> LanDockerHost.validate("nas", "not-an-ip", 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hostIp");
    }

    @Test
    void validate_portBelowOne_throws() {
        assertThatThrownBy(() -> LanDockerHost.validate("nas", "192.168.3.50", 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("port");
    }

    @Test
    void validate_portAbove65535_throws() {
        assertThatThrownBy(() -> LanDockerHost.validate("nas", "192.168.3.50", 70000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("port");
    }

    @Test
    void record_fieldsAreAccessible() {
        LanDockerHost host = new LanDockerHost("nas", "192.168.3.50", 2375);

        assertThat(host.name()).isEqualTo("nas");
        assertThat(host.hostIp()).isEqualTo("192.168.3.50");
        assertThat(host.port()).isEqualTo(2375);
    }
}
