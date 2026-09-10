package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Vaier's <b>Memory</b> (#360): facts worth keeping across conversations, for the whole fleet — where the
 * photos live, what the operator prefers — whether the operator said them or Chat found them by looking.
 * Every fact is visible and removable; the decisions here are what counts as a fact and how many there may be.
 */
class MemoryTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void aFactIsRememberedWithAnIdAndWhen() {
        Memory memory = Memory.empty().remember("The photos live under /volume1/photo on the NAS.", NOW);

        assertThat(memory.facts()).hasSize(1);
        Memory.Fact fact = memory.facts().get(0);
        assertThat(fact.id()).matches("[a-z0-9]{6}");
        assertThat(fact.text()).isEqualTo("The photos live under /volume1/photo on the NAS.");
        assertThat(fact.rememberedAtEpochMs()).isEqualTo(NOW);
    }

    @Test
    void rememberingLeavesTheMemoryItCameFromAlone() {
        Memory before = Memory.empty();
        Memory after = before.remember("a", NOW);

        assertThat(before.facts()).isEmpty();
        assertThat(after.facts()).hasSize(1);
    }

    @Test
    void theSameFactTwiceIsOneFact() {
        Memory memory = Memory.empty().remember("Photos live under /volume1/photo.", NOW)
            .remember("  photos live under /volume1/photo.  ", NOW + 1);

        assertThat(memory.facts()).hasSize(1);
    }

    @Test
    void nothingIsNotAFact_andNeitherIsAnEssay() {
        assertThatThrownBy(() -> Memory.empty().remember("  ", NOW))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Say what to remember.");
        assertThatThrownBy(() -> Memory.empty().remember("x".repeat(Memory.MAX_CHARS + 1), NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A memory is one fact, at most " + Memory.MAX_CHARS + " characters.");
    }

    @Test
    void theMemoryHasARoof_andSaysSoRatherThanForgettingOnItsOwn() {
        Memory full = Memory.empty();
        for (int i = 0; i < Memory.MAX_FACTS; i++) {
            full = full.remember("fact " + i, NOW);
        }

        Memory finalFull = full;
        assertThatThrownBy(() -> finalFull.remember("one more", NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Vaier's memory is full; forget something first.");
    }

    @Test
    void aFactIsForgottenByItsId_andAnUnknownIdIsSaidSo() {
        Memory memory = Memory.empty().remember("a", NOW).remember("b", NOW);
        String id = memory.facts().get(0).id();

        assertThat(memory.forget(id).facts()).extracting(Memory.Fact::text).containsExactly("b");
        assertThatThrownBy(() -> memory.forget("nope"))
            .isInstanceOf(NotFoundException.class).hasMessage("Vaier has no memory with the id nope.");
    }

    /** What the model reads: every fact with its id, so it can forget one by name. */
    @Test
    void forThePrompt_everyFactIsListedWithItsId() {
        Memory memory = Memory.empty().remember("Photos live under /volume1/photo.", NOW);
        String id = memory.facts().get(0).id();

        assertThat(memory.forPrompt()).isEqualTo("- [" + id + "] Photos live under /volume1/photo.\n");
        assertThat(Memory.empty().forPrompt()).isEqualTo("(nothing yet)\n");
    }

    @Test
    void factsAreListedOldestFirst() {
        Memory memory = Memory.empty().remember("first", NOW).remember("second", NOW + 1);

        assertThat(memory.facts()).extracting(Memory.Fact::text).containsExactly("first", "second");
        assertThat(new Memory(List.of()).facts()).isEmpty();
    }
}
