package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConceptTest {

    @Test
    void derivesSlugFromTermLowercasingAndHyphenating() {
        Concept concept = Concept.of("LAN CIDR", "def", "why");

        assertThat(concept.slug()).isEqualTo("lan-cidr");
        assertThat(concept.term()).isEqualTo("LAN CIDR");
        assertThat(concept.definition()).isEqualTo("def");
        assertThat(concept.whyYouCare()).isEqualTo("why");
    }

    @Test
    void collapsesNonAlphanumericRunsIntoASingleHyphenAndTrims() {
        assertThat(Concept.of("Out-of-date config", "d", "w").slug())
            .isEqualTo("out-of-date-config");
        assertThat(Concept.of("Forward-auth", "d", "w").slug())
            .isEqualTo("forward-auth");
        assertThat(Concept.of("  Public host  ", "d", "w").slug())
            .isEqualTo("public-host");
        assertThat(Concept.of("Four-state machine-icon colour", "d", "w").slug())
            .isEqualTo("four-state-machine-icon-colour");
    }
}
