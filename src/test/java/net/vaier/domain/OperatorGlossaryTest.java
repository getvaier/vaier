package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    void holdsOnlyTheFifteenWordsAnOperatorMeetsInTheUi() {
        // #377: 72 terms was a developer glossary in plain clothes. The page keeps the words the UI says
        // after the plain-words renames, and UBIQUITOUS_LANGUAGE.md keeps the rest.
        assertThat(OperatorGlossary.groups()).flatExtracting(ConceptGroup::concepts).extracting(Concept::term)
            .containsExactly("Machine", "Vaier server", "VPN", "Vaier app", "Switched off on purpose",
                "Apps", "Websites",
                "Launchpad", "Sign in", "Trusted address",
                "Backup server", "Incomplete backup", "Back up as root",
                "Needs you", "Marvin");
    }

    @Test
    void explainsBackUpAsRoot_atTheSlugTheNudgeLinksTo() {
        // #334: the ~700 words that used to live in docs/BACKUP.md are the operator's question, not a
        // developer's, so the plain-language version belongs here — and the machine's nudge links straight
        // at its slug, so the slug is part of the contract, not an implementation detail.
        Concept concept = OperatorGlossary.groups().stream()
            .flatMap(g -> g.concepts().stream())
            .filter(c -> c.term().equals("Back up as root"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the Concepts page must explain Back up as root"));

        assertThat(concept.slug()).isEqualTo("back-up-as-root");
        // It has to be honest about the grant: this is the entry an operator reads before saying yes.
        assertThat(concept.definition() + " " + concept.whyYouCare()).contains("root");
    }
}
