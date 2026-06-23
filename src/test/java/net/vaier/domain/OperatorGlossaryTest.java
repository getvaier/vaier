package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorGlossaryTest {

    @Test
    void everyConceptTermAppearsVerbatimAsABoldEntryInTheUbiquitousLanguageDoc() throws IOException {
        String doc = Files.readString(Path.of("UBIQUITOUS_LANGUAGE.md"));

        for (ConceptGroup group : OperatorGlossary.groups()) {
            for (Concept concept : group.concepts()) {
                assertThat(doc)
                    .as("term '%s' must appear as **%s** in UBIQUITOUS_LANGUAGE.md",
                        concept.term(), concept.term())
                    .contains("**" + concept.term() + "**");
            }
        }
    }

    @Test
    void hasNoDuplicateSlugsAcrossAllGroups() {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (ConceptGroup group : OperatorGlossary.groups()) {
            for (Concept concept : group.concepts()) {
                if (!seen.add(concept.slug())) {
                    duplicates.add(concept.slug());
                }
            }
        }
        assertThat(duplicates).as("duplicate concept slugs").isEmpty();
    }

    @Test
    void everyConceptHasNonBlankDefinitionAndWhyYouCare() {
        for (ConceptGroup group : OperatorGlossary.groups()) {
            assertThat(group.title()).isNotBlank();
            assertThat(group.concepts()).isNotEmpty();
            for (Concept concept : group.concepts()) {
                assertThat(concept.term()).isNotBlank();
                assertThat(concept.definition()).isNotBlank();
                assertThat(concept.whyYouCare()).isNotBlank();
            }
        }
    }

    @Test
    void exposesGroupedConcepts() {
        assertThat(OperatorGlossary.groups()).isNotEmpty();
    }
}
