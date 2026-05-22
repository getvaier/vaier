package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VpnSubnetTest {

    private static final VpnSubnet SUBNET = new VpnSubnet("10.13.13.0/24");

    @Test
    void nextAvailableIp_withNoPeers_isDotTwo() {
        // .1 is reserved for the Vaier server, so the first peer starts at .2.
        assertThat(SUBNET.nextAvailableIp(List.of())).isEqualTo("10.13.13.2");
    }

    @Test
    void nextAvailableIp_neverReturnsTheServerAddress() {
        assertThat(SUBNET.nextAvailableIp(List.of("10.13.13.1"))).isEqualTo("10.13.13.2");
    }

    @Test
    void nextAvailableIp_isOnePastTheHighestAssignedOctet() {
        assertThat(SUBNET.nextAvailableIp(List.of("10.13.13.2", "10.13.13.5", "10.13.13.3")))
            .isEqualTo("10.13.13.6");
    }

    @Test
    void nextAvailableIp_ignoresMalformedAddresses() {
        assertThat(SUBNET.nextAvailableIp(List.of("not-an-ip", "10.13.13.4", "10.13.13")))
            .isEqualTo("10.13.13.5");
    }
}
