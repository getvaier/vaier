package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedImageTest {

    @Test
    void labelReadsTheImageThenTheMachineItRunsOn() {
        // The whole point of #57's refinement: an operator must be able to tell WHICH machine to act on.
        ScopedImage scoped = new ScopedImage("Apalveien 5", "vaultwarden/server:latest");

        assertThat(scoped.label()).isEqualTo("vaultwarden/server:latest on Apalveien 5");
    }

    @Test
    void theSameImageOnTwoMachinesAreDistinctScopedImages() {
        // The tracked unit is image-on-a-machine, not image: two machines running the same tag are two things.
        ScopedImage onA = new ScopedImage("Apalveien 5", "vaultwarden/server:latest");
        ScopedImage onB = new ScopedImage("Colina 27", "vaultwarden/server:latest");

        assertThat(onA).isNotEqualTo(onB);
    }
}
