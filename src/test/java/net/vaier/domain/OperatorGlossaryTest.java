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

    @Test
    void explainsBackupRepository_theOneWordTheRestOfTheUiRefusesToSay() {
        // #339 retires "backup repository" from every operator-facing surface, which leaves an operator who
        // meets the word — in a passphrase prompt, in this document, in a borg command — nowhere to go. The
        // Concepts page is the exemption: mechanism words are allowed to exist here, and only here.
        Concept concept = OperatorGlossary.groups().stream()
            .filter(g -> g.title().equals("Backups"))
            .flatMap(g -> g.concepts().stream())
            .filter(c -> c.term().equals("Backup repository"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the Concepts page must explain Backup repository"));

        // And it has to say why the operator never sees the word anywhere else.
        assertThat(concept.whyYouCare()).contains("whose backups");
    }

    @Test
    void explainsAsk_theOnePaneWhoseWordsCameFromNowhereElseInTheUi() {
        // #360: Ask arrived with five terms an operator meets on its pane and in Settings — and none of them
        // was on the Concepts page, so the one place that explains Vaier's words had nothing to say about
        // the pane that talks. The group carries every term the glossary doc has for it, verbatim.
        ConceptGroup ask = OperatorGlossary.groups().stream()
            .filter(g -> g.title().equals("Ask"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the Concepts page must have an Ask group"));

        assertThat(ask.concepts()).extracting(Concept::term)
            .containsExactly("Ask", "Anthropic API key", "Ask tool", "Read-only command", "Ask action",
                "Confirmation", "Conversation");
        // The two promises an operator needs before pasting a key: the key never leaves for anywhere but
        // the Claude API, and the shell tool cannot change a machine.
        Concept key = ask.concepts().get(1);
        assertThat(key.definition() + " " + key.whyYouCare()).contains("Claude API");
        Concept command = ask.concepts().get(3);
        assertThat(command.definition() + " " + command.whyYouCare()).contains("sudo").contains("never change");
        // And the one an operator needs before clicking a card: nothing ran until they did.
        Concept confirmation = ask.concepts().get(5);
        assertThat(confirmation.definition() + " " + confirmation.whyYouCare()).contains("click");
        // Slice 3: the conversation is kept, per operator, and a long one is shortened by the model.
        Concept conversation = ask.concepts().get(6);
        assertThat(conversation.definition() + " " + conversation.whyYouCare())
            .contains("kept").contains("summary").doesNotContain("keeps none of it");
    }
}
