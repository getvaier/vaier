package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The distinction the scrape already knows and used to throw away (#356): a machine that answered with no
 * containers, and a machine that did not answer at all, are the same data and must never be the same
 * verdict.
 */
class ContainerObservationTest {

    private static final MachineId APALVEIEN = TestMachineIds.of("apalveien");

    private static DockerService running(String name) {
        return new DockerService("id-" + name, name, "ghcr.io/" + name, "v", List.of(), List.of(),
            "running");
    }

    @Test
    void aScrapeThatReachedTheDaemonAnswered() {
        assertThat(ContainerObservation.of(APALVEIEN.value(), "OK", List.of(running("webtrees"))))
            .get()
            .satisfies(observation -> {
                assertThat(observation.answered()).isTrue();
                assertThat(observation.machineId()).isEqualTo(APALVEIEN);
                assertThat(observation.containers()).hasSize(1);
            });
    }

    @Test
    void anUnreachableMachineDidNotAnswer() {
        assertThat(ContainerObservation.of(APALVEIEN.value(), "UNREACHABLE", List.of()))
            .get()
            .satisfies(observation -> assertThat(observation.answered()).isFalse());
    }

    @Test
    void aPeerInNoMachineRegistryHasNothingToFileAStandingAgainst() {
        // A live WireGuard peer with no stored config reports no identity. Read, never minted.
        assertThat(ContainerObservation.of(null, "OK", List.of(running("webtrees")))).isEmpty();
    }

    @Test
    void anEmptyReadingOfVaiersOwnHostIsNoAnswer() {
        // Vaier itself runs in a container on this machine, so "no containers here" can only mean the
        // local scrape failed. Read as an answer it would look like the whole stack vanishing at once.
        assertThat(ContainerObservation.ofVaierServer(APALVEIEN, List.of()).answered()).isFalse();
        assertThat(ContainerObservation.ofVaierServer(APALVEIEN, List.of(running("vaier"))).answered())
            .isTrue();
    }
}
