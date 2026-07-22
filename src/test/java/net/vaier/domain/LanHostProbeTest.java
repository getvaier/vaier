package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LanHostProbeTest {

    @Test
    void notReachable_carriesNoHostAndNoRoute() {
        LanHostProbe probe = LanHostProbe.notReachable();

        assertThat(probe.reachable()).isFalse();
        assertThat(probe.host()).isNull();
        assertThat(probe.routedVia()).isNull();
    }

    @Test
    void reached_carriesTheProbedHostAndTheRelayItWasRoutedThrough() {
        DiscoveredLanMachine host = new DiscoveredLanMachine(
            "192.168.3.50", "synology-nas", List.of(22, 2375), "apalveien5");

        LanHostProbe probe = LanHostProbe.reached(host, "Apalveien 5");

        assertThat(probe.reachable()).isTrue();
        assertThat(probe.host()).isEqualTo(host);
        assertThat(probe.routedVia()).isEqualTo("Apalveien 5");
    }
}
