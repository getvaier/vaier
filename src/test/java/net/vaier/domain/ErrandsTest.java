package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every <b>errand</b> Vaier keeps (#360), for every operator: who may cancel what, how many there may be, and
 * which ones are due. The scheduler asks; the decisions are here.
 */
class ErrandsTest {

    private static final ZoneId OSLO = ZoneId.of("Europe/Oslo");
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 9, 10, 15, 59, 0, 0, OSLO);
    private static final Operator GEIR = Operator.of("geir@example.com");
    private static final Operator SOMEONE_ELSE = Operator.of("someone@else.com");
    private static final Rhythm DAILY = Rhythm.parse("daily 08:00");

    @Test
    void nothingSentYetIsNoErrands() {
        assertThat(Errands.empty().errands()).isEmpty();
        assertThat(Errands.empty().of(GEIR)).isEmpty();
        assertThat(Errands.empty().due(NOW.toInstant())).isEmpty();
    }

    @Test
    void anErrandIsAddedUnderTheOperatorWhoAskedForIt_withItsFirstTimeSet() {
        Errands errands = Errands.empty().add(GEIR, "  Check the NAS.  ", DAILY, NOW);

        assertThat(errands.errands()).hasSize(1);
        Errand added = errands.errands().get(0);
        assertThat(added.operator()).isEqualTo(GEIR);
        assertThat(added.instruction()).isEqualTo("Check the NAS.");
        assertThat(added.rhythm()).isEqualTo(DAILY);
        assertThat(added.nextDue()).isEqualTo(DAILY.firstDue(NOW));
        assertThat(added.createdAtEpochMs()).isEqualTo(NOW.toInstant().toEpochMilli());
        assertThat(added.id()).matches("[a-z0-9]{6}");
    }

    @Test
    void eachErrandGetsAnIdOfItsOwn() {
        Errands errands = Errands.empty().add(GEIR, "one", DAILY, NOW).add(GEIR, "two", DAILY, NOW);

        assertThat(errands.errands()).extracting(Errand::id).doesNotHaveDuplicates();
    }

    /** A roof that is said, never a silent forgetting — the sentence goes to the model. */
    @Test
    void theErrandAfterTheLastOneIsRefusedInASentence() {
        Errands errands = Errands.empty();
        for (int i = 0; i < Errands.MAX_ERRANDS; i++) {
            errands = errands.add(GEIR, "errand " + i, DAILY, NOW);
        }
        Errands full = errands;

        assertThatThrownBy(() -> full.add(GEIR, "one more", DAILY, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(String.valueOf(Errands.MAX_ERRANDS));
    }

    @Test
    void anOperatorSeesOnlyTheirOwnErrands() {
        Errands errands = Errands.empty().add(GEIR, "mine", DAILY, NOW).add(SOMEONE_ELSE, "theirs", DAILY, NOW);

        assertThat(errands.of(GEIR)).extracting(Errand::instruction).containsExactly("mine");
        assertThat(errands.of(SOMEONE_ELSE)).extracting(Errand::instruction).containsExactly("theirs");
    }

    @Test
    void cancellingDropsTheErrand() {
        Errands errands = Errands.empty().add(GEIR, "mine", DAILY, NOW);
        String id = errands.errands().get(0).id();

        assertThat(errands.cancel(GEIR, id).errands()).isEmpty();
    }

    /** Another operator's errand is not theirs to cancel, and is answered as one that does not exist. */
    @Test
    void anotherOperatorsErrandCannotBeCancelled() {
        Errands errands = Errands.empty().add(SOMEONE_ELSE, "theirs", DAILY, NOW);
        String id = errands.errands().get(0).id();

        assertThatThrownBy(() -> errands.cancel(GEIR, id))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Vaier has no errand with the id " + id + ".");
        assertThat(errands.errands()).hasSize(1);
    }

    @Test
    void cancellingAnErrandThatIsNotThereSaysSo() {
        assertThatThrownBy(() -> Errands.empty().cancel(GEIR, "nope"))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Vaier has no errand with the id nope.");
    }

    /** The scheduler asks about every operator's errands at once; whose it is matters when it runs. */
    @Test
    void theDueOnesAreEveryOperatorsThatHaveComeRound() {
        Errands errands = Errands.empty()
            .add(GEIR, "soon", Rhythm.parse("daily 16:00"), NOW)
            .add(SOMEONE_ELSE, "later", Rhythm.parse("daily 20:00"), NOW);

        Instant atFour = ZonedDateTime.of(2026, 9, 10, 16, 0, 0, 0, OSLO).toInstant();
        assertThat(errands.due(atFour)).extracting(Errand::instruction).containsExactly("soon");
        assertThat(errands.due(ZonedDateTime.of(2026, 9, 10, 20, 0, 0, 0, OSLO).toInstant()))
            .extracting(Errand::instruction).containsExactly("soon", "later");
    }

    /** After a run a repeating errand is kept, moved on; a once-errand is gone. */
    @Test
    void afterARunARepeatingErrandMovesOnAndAOnceErrandIsDropped() {
        Errands errands = Errands.empty()
            .add(GEIR, "every day", DAILY, NOW)
            .add(GEIR, "just once", Rhythm.parse("once 2026-09-12T08:00"), NOW);
        String repeating = errands.errands().get(0).id();
        String once = errands.errands().get(1).id();
        ZonedDateTime ranAtEight = ZonedDateTime.of(2026, 9, 11, 8, 0, 0, 0, OSLO);

        Errands after = errands.afterRun(repeating, ranAtEight, "reported").afterRun(once, ranAtEight, "reported");

        assertThat(after.errands()).extracting(Errand::instruction).containsExactly("every day");
        assertThat(after.errands().get(0).lastOutcome()).isEqualTo("reported");
        assertThat(after.errands().get(0).nextDue())
            .isEqualTo(ZonedDateTime.of(2026, 9, 12, 8, 0, 0, 0, OSLO).toInstant());
    }

    /** An errand cancelled while it was running is not resurrected by its own run finishing. */
    @Test
    void afterARunOfAnErrandThatIsNoLongerThereChangesNothing() {
        Errands errands = Errands.empty().add(GEIR, "mine", DAILY, NOW);

        assertThat(errands.afterRun("gone", NOW, "reported")).isEqualTo(errands);
    }

    /** What the model reads: this operator's errands, with ids, so it can cancel one by name. */
    @Test
    void whatTheModelReadsIsThisOperatorsErrandsWithTheirIds() {
        Errands errands = Errands.empty().add(GEIR, "Check the NAS.", DAILY, NOW)
            .add(SOMEONE_ELSE, "Not mine.", DAILY, NOW);
        String id = errands.errands().get(0).id();

        assertThat(errands.forPrompt(GEIR))
            .isEqualTo("- [" + id + "] Every day at 08:00: Check the NAS.\n");
        assertThat(errands.forPrompt(GEIR)).doesNotContain("Not mine.");
        assertThat(Errands.empty().forPrompt(GEIR)).isEqualTo("(none)\n");
    }

    @Test
    void itHoldsUpWhenGivenNothingAtAll() {
        assertThat(new Errands(null).errands()).isEmpty();
        assertThat(new Errands(List.of()).of(GEIR)).isEmpty();
    }
}
