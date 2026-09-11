package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The <b>rhythm</b> of an <b>errand</b> (#360): the one string the model writes to say when Marvin should do
 * something, and every decision that follows from it — when it first falls due, when it falls due again, and
 * how it reads back to the operator.
 *
 * <p>The clock is handed in, never read here: "the next 08:00" is a different instant in Oslo than in UTC,
 * and the domain does not look at clocks.
 */
class RhythmTest {

    private static final ZoneId OSLO = ZoneId.of("Europe/Oslo");

    /** Thursday 10 September 2026, 15:59 Oslo — the same moment the prompt tests pin. */
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 9, 10, 15, 59, 0, 0, OSLO);

    private static Instant osloInstant(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, OSLO).toInstant();
    }

    // --- the four shapes -----------------------------------------------------------------------------

    @Test
    void once_fallsDueAtTheMomentItNames() {
        Rhythm rhythm = Rhythm.parse("once 2026-09-12T08:00");

        assertThat(rhythm.firstDue(NOW)).isEqualTo(osloInstant(2026, 9, 12, 8, 0));
        assertThat(rhythm.describe()).isEqualTo("Once, on 12 September 2026 at 08:00");
    }

    /** A once-errand that has already been run is over; there is no next time. */
    @Test
    void once_hasNoNextTime() {
        assertThat(Rhythm.parse("once 2026-09-12T08:00").nextAfter(NOW)).isEmpty();
    }

    /** An errand Vaier could only run in the past is refused while the operator is still there to hear it. */
    @Test
    void once_refusesATimeThatHasPassed() {
        assertThatThrownBy(() -> Rhythm.parse("once 2026-09-10T08:00").firstDue(NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("That time has passed.");
    }

    @Test
    void daily_fallsDueAtTheNextOccurrenceOfItsTime() {
        Rhythm rhythm = Rhythm.parse("daily 08:00");

        assertThat(rhythm.firstDue(NOW)).isEqualTo(osloInstant(2026, 9, 11, 8, 0));
        assertThat(rhythm.describe()).isEqualTo("Every day at 08:00");
    }

    /** Later the same day still counts: 15:59 and "daily 20:00" means tonight, not tomorrow. */
    @Test
    void daily_fallsDueTodayWhenItsTimeIsStillToCome() {
        assertThat(Rhythm.parse("daily 20:00").firstDue(NOW)).isEqualTo(osloInstant(2026, 9, 10, 20, 0));
    }

    @Test
    void weekly_fallsDueOnTheNextSuchWeekday() {
        Rhythm rhythm = Rhythm.parse("weekly monday 08:00");

        assertThat(rhythm.firstDue(NOW)).isEqualTo(osloInstant(2026, 9, 14, 8, 0));
        assertThat(rhythm.describe()).isEqualTo("Every Monday at 08:00");
    }

    /** Today is Thursday: "weekly thursday 20:00" is tonight, and "weekly thursday 08:00" is next week. */
    @Test
    void weekly_onTodaysWeekdayIsTodayOnlyWhileItsTimeIsStillToCome() {
        assertThat(Rhythm.parse("weekly thursday 20:00").firstDue(NOW)).isEqualTo(osloInstant(2026, 9, 10, 20, 0));
        assertThat(Rhythm.parse("weekly thursday 08:00").firstDue(NOW)).isEqualTo(osloInstant(2026, 9, 17, 8, 0));
    }

    @Test
    void monthly_fallsDueOnItsDayOfTheNextSuchMonth() {
        Rhythm rhythm = Rhythm.parse("monthly 1 08:00");

        assertThat(rhythm.firstDue(NOW)).isEqualTo(osloInstant(2026, 10, 1, 8, 0));
        assertThat(rhythm.describe()).isEqualTo("On the 1st of every month at 08:00");
    }

    /** A day past the month's length is the month's last day, never a skipped month. */
    @Test
    void monthly_clampsADayTheMonthDoesNotHave() {
        ZonedDateTime februaryFirst = ZonedDateTime.of(2026, 2, 1, 9, 0, 0, 0, OSLO);

        assertThat(Rhythm.parse("monthly 31 08:00").firstDue(februaryFirst))
            .isEqualTo(osloInstant(2026, 2, 28, 8, 0));
        assertThat(Rhythm.parse("monthly 31 08:00").describe())
            .isEqualTo("On the 31st of every month at 08:00");
    }

    /** The ordinals a person writes, not "the 2 of every month". */
    @Test
    void monthly_readsItsDayAsAnOrdinal() {
        assertThat(Rhythm.parse("monthly 2 08:00").describe()).startsWith("On the 2nd");
        assertThat(Rhythm.parse("monthly 3 08:00").describe()).startsWith("On the 3rd");
        assertThat(Rhythm.parse("monthly 4 08:00").describe()).startsWith("On the 4th");
        assertThat(Rhythm.parse("monthly 11 08:00").describe()).startsWith("On the 11th");
        assertThat(Rhythm.parse("monthly 12 08:00").describe()).startsWith("On the 12th");
        assertThat(Rhythm.parse("monthly 13 08:00").describe()).startsWith("On the 13th");
        assertThat(Rhythm.parse("monthly 21 08:00").describe()).startsWith("On the 21st");
        assertThat(Rhythm.parse("monthly 22 08:00").describe()).startsWith("On the 22nd");
        assertThat(Rhythm.parse("monthly 23 08:00").describe()).startsWith("On the 23rd");
    }

    // --- falling due again ---------------------------------------------------------------------------

    /** The next time is strictly after the run, so an errand that has just run is not instantly due again. */
    @Test
    void aRepeatingRhythmFallsDueAgainStrictlyAfterTheRun() {
        ZonedDateTime ranAtEight = ZonedDateTime.of(2026, 9, 11, 8, 0, 0, 0, OSLO);

        assertThat(Rhythm.parse("daily 08:00").nextAfter(ranAtEight))
            .contains(osloInstant(2026, 9, 12, 8, 0));
        assertThat(Rhythm.parse("weekly friday 08:00").nextAfter(ranAtEight))
            .contains(osloInstant(2026, 9, 18, 8, 0));
        assertThat(Rhythm.parse("monthly 11 08:00").nextAfter(ranAtEight))
            .contains(osloInstant(2026, 10, 11, 8, 0));
    }

    /** A repeating errand that fell behind — Vaier was down — catches up to the next time from now, once. */
    @Test
    void aRepeatingRhythmThatFellBehindFallsDueAtTheNextTimeFromNow() {
        ZonedDateTime lateRun = ZonedDateTime.of(2026, 9, 11, 23, 30, 0, 0, OSLO);

        assertThat(Rhythm.parse("daily 08:00").nextAfter(lateRun)).contains(osloInstant(2026, 9, 12, 8, 0));
    }

    // --- what the model may write -------------------------------------------------------------------

    @Test
    void itReadsTheRhythmWhateverCaseTheModelWroteItIn() {
        assertThat(Rhythm.parse("  DAILY 08:00 ").describe()).isEqualTo("Every day at 08:00");
        assertThat(Rhythm.parse("Weekly Monday 08:00").describe()).isEqualTo("Every Monday at 08:00");
        assertThat(Rhythm.parse("ONCE 2026-09-12T08:00").describe()).isEqualTo("Once, on 12 September 2026 at 08:00");
    }

    /** It goes to the model, so every refusal is a sentence Marvin can repeat, and it names the four shapes. */
    @Test
    void aRhythmItCannotReadIsRefusedInASentenceNamingAllFourShapes() {
        for (String nonsense : new String[] {"", "   ", "every morning", "daily", "daily 8am",
                                             "weekly caturday 08:00", "monthly 08:00", "once 2026-09-12 08:00"}) {
            assertThatThrownBy(() -> Rhythm.parse(nonsense))
                .as("rhythm '%s'", nonsense)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("once 2026-09-12T08:00")
                .hasMessageContaining("daily 08:00")
                .hasMessageContaining("weekly monday 08:00")
                .hasMessageContaining("monthly 1 08:00");
        }
    }

    @Test
    void aMonthlyDayOutsideTheMonthIsRefused() {
        assertThatThrownBy(() -> Rhythm.parse("monthly 0 08:00"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1 to 31");
        assertThatThrownBy(() -> Rhythm.parse("monthly 32 08:00"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1 to 31");
    }

    // --- kept on a file, and read back --------------------------------------------------------------

    /** What the adapter writes is what {@link Rhythm#parse} reads, so a rhythm survives a restart. */
    @Test
    void aRhythmRoundTripsThroughItsOwnSourceString() {
        for (String source : new String[] {"once 2026-09-12T08:00", "daily 08:00", "weekly monday 08:00",
                                           "monthly 1 08:00"}) {
            Rhythm rhythm = Rhythm.parse(source);
            assertThat(rhythm.source()).isEqualTo(source);
            assertThat(Rhythm.parse(rhythm.source())).isEqualTo(rhythm);
        }
    }

    /** Said in the middle of a sentence, so only the leading word loses its capital. */
    @Test
    void itReadsInsideASentenceWithoutShoutingTheWeekday() {
        assertThat(Rhythm.parse("daily 08:00").inSentence()).isEqualTo("every day at 08:00");
        assertThat(Rhythm.parse("weekly monday 08:00").inSentence()).isEqualTo("every Monday at 08:00");
        assertThat(Rhythm.parse("monthly 1 08:00").inSentence()).isEqualTo("on the 1st of every month at 08:00");
        assertThat(Rhythm.parse("once 2026-09-12T08:00").inSentence())
            .isEqualTo("once, on 12 September 2026 at 08:00");
    }

    /** A once-rhythm is over when it has run; every other kind goes on. */
    @Test
    void onlyOnceIsEverOver() {
        assertThat(Rhythm.parse("once 2026-09-12T08:00").repeats()).isFalse();
        assertThat(Rhythm.parse("daily 08:00").repeats()).isTrue();
        Optional<Instant> next = Rhythm.parse("daily 08:00").nextAfter(NOW);
        assertThat(next).isPresent();
    }
}
