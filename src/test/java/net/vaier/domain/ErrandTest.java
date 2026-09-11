package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One <b>errand</b> (#360): something the operator sent Marvin to do later, once or on a <b>rhythm</b>, while
 * nobody is watching. Whether it is due, what it becomes once it has run, and how it reads back are all its
 * own decisions — the scheduler only asks.
 */
class ErrandTest {

    private static final ZoneId OSLO = ZoneId.of("Europe/Oslo");
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 9, 10, 15, 59, 0, 0, OSLO);
    private static final Operator GEIR = Operator.of("geir@example.com");

    private static Errand errand(String rhythm) {
        Rhythm parsed = Rhythm.parse(rhythm);
        return Errand.builder().id("ab12cd").operator(GEIR)
            .instruction("Tell me if any machine has operating system updates.")
            .rhythm(parsed).nextDue(parsed.firstDue(NOW))
            .createdAtEpochMs(NOW.toInstant().toEpochMilli()).build();
    }

    @Test
    void itIsDueOnceItsTimeHasComeAndNotBefore() {
        Errand errand = errand("daily 08:00");

        assertThat(errand.isDue(NOW.toInstant())).isFalse();
        assertThat(errand.isDue(errand.nextDue().minusSeconds(1))).isFalse();
        assertThat(errand.isDue(errand.nextDue())).isTrue();
        assertThat(errand.isDue(errand.nextDue().plusSeconds(1))).isTrue();
    }

    /** Run, a repeating errand moves on: the next time, when it last ran, and how that run went. */
    @Test
    void aRepeatingErrandThatHasRunMovesOnToItsNextTime() {
        ZonedDateTime ranAtEight = ZonedDateTime.of(2026, 9, 11, 8, 0, 0, 0, OSLO);

        Optional<Errand> advanced = errand("daily 08:00").ran(ranAtEight, "reported");

        assertThat(advanced).isPresent();
        assertThat(advanced.get().nextDue())
            .isEqualTo(ZonedDateTime.of(2026, 9, 12, 8, 0, 0, 0, OSLO).toInstant());
        assertThat(advanced.get().lastRunAtEpochMs()).isEqualTo(ranAtEight.toInstant().toEpochMilli());
        assertThat(advanced.get().lastOutcome()).isEqualTo("reported");
        assertThat(advanced.get().id()).isEqualTo("ab12cd");
        assertThat(advanced.get().instruction()).isEqualTo(errand("daily 08:00").instruction());
    }

    /** A once-errand that has run is over — there is nothing left to keep. */
    @Test
    void aOnceErrandThatHasRunIsOver() {
        assertThat(errand("once 2026-09-12T08:00").ran(NOW, "reported")).isEmpty();
    }

    @Test
    void itReadsBackAsItsRhythmAndItsInstruction() {
        assertThat(errand("daily 08:00").describe())
            .isEqualTo("[ab12cd] Every day at 08:00: Tell me if any machine has operating system updates.");
    }

    /** What the model is told when one is added, so it can repeat it to the operator. */
    @Test
    void itSaysSoWhenItHasJustBeenAdded() {
        assertThat(errand("daily 08:00").addedSentence())
            .isEqualTo("Added [ab12cd]: Every day at 08:00 — Tell me if any machine has operating system "
                + "updates.");
    }

    // --- what an errand may be ----------------------------------------------------------------------

    @Test
    void anInstructionIsTrimmedAndMustSaySomething() {
        assertThat(errand("daily 08:00").toBuilder().instruction("  check the NAS  ").build().instruction())
            .isEqualTo("check the NAS");
        assertThatThrownBy(() -> errand("daily 08:00").toBuilder().instruction("   ").build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Say what");
    }

    @Test
    void anInstructionLongerThanTheRoofIsRefusedInASentence() {
        assertThatThrownBy(() -> errand("daily 08:00").toBuilder()
            .instruction("x".repeat(Errand.MAX_CHARS + 1)).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(String.valueOf(Errand.MAX_CHARS));
    }

    @Test
    void anErrandBelongsToAnOperatorAndHasARhythmAndATime() {
        assertThatThrownBy(() -> errand("daily 08:00").toBuilder().operator(null).build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> errand("daily 08:00").toBuilder().rhythm(null).build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> errand("daily 08:00").toBuilder().nextDue(null).build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Never run yet is not a run that went badly: both are read from the pane, and they are different. */
    @Test
    void anErrandThatHasNeverRunSaysNothingAboutARun() {
        Errand fresh = errand("daily 08:00");

        assertThat(fresh.lastRunAtEpochMs()).isNull();
        assertThat(fresh.lastOutcome()).isNull();
    }

    @Test
    void anIdIsSixCharacters() {
        assertThat(errand("daily 08:00").id()).matches("[a-z0-9]{6}");
    }

    @Test
    void itKnowsWhoseItIs() {
        Errand errand = errand("daily 08:00");

        assertThat(errand.belongsTo(GEIR)).isTrue();
        assertThat(errand.belongsTo(Operator.of("someone@else.com"))).isFalse();
    }

    @Test
    void anInstantIsAllTheSchedulerNeedsToAsk() {
        Instant due = errand("daily 08:00").nextDue();

        assertThat(due).isAfter(NOW.toInstant());
    }
}
