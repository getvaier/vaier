package net.vaier.application.service;

import net.vaier.domain.ConceptGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConceptsServiceTest {

    @Test
    void returnsTheCuratedOperatorGlossaryGroups() {
        List<ConceptGroup> groups = new ConceptsService().getConcepts();

        assertThat(groups).isNotEmpty();
        assertThat(groups.get(0).concepts()).isNotEmpty();
    }
}
