package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MachineStatusTest {

    @Test
    void forLanServer_unknownReachability_returnsUnknown() {
        assertThat(MachineStatus.forLanServer(false, false, false, false)).isEqualTo(MachineStatus.UNKNOWN);
        // The other inputs don't matter once reachability is unknown.
        assertThat(MachineStatus.forLanServer(false, true, true, true)).isEqualTo(MachineStatus.UNKNOWN);
    }

    @Test
    void forLanServer_notReachable_returnsDown() {
        assertThat(MachineStatus.forLanServer(true, false, true, true)).isEqualTo(MachineStatus.DOWN);
        // Docker scrape result doesn't matter when the host is unreachable.
        assertThat(MachineStatus.forLanServer(true, false, true, false)).isEqualTo(MachineStatus.DOWN);
    }

    @Test
    void forLanServer_reachableNonDocker_alwaysOk() {
        // A host without Docker has no secondary signal — it never degrades.
        assertThat(MachineStatus.forLanServer(true, true, false, false)).isEqualTo(MachineStatus.OK);
        assertThat(MachineStatus.forLanServer(true, true, false, true)).isEqualTo(MachineStatus.OK);
    }

    @Test
    void forLanServer_reachableDockerScrapeOk_isOk() {
        assertThat(MachineStatus.forLanServer(true, true, true, true)).isEqualTo(MachineStatus.OK);
    }

    @Test
    void forLanServer_reachableDockerScrapeFailing_isDegraded() {
        assertThat(MachineStatus.forLanServer(true, true, true, false)).isEqualTo(MachineStatus.DEGRADED);
    }
}
