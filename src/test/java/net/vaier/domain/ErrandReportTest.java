package net.vaier.domain;

import net.vaier.domain.ConversationTurn.Role;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What comes of an <b>errand</b> Marvin has run (#360): the mail, the turn in the conversation, and the one
 * decision that keeps a watch from becoming noise — whether there was anything to say at all.
 */
class ErrandReportTest {

    private static final ZoneId OSLO = ZoneId.of("Europe/Oslo");
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 9, 10, 15, 59, 0, 0, OSLO);

    private static Errand errand(String instruction) {
        Rhythm rhythm = Rhythm.parse("daily 08:00");
        return Errand.builder().id("ab12cd").operator(Operator.of("geir@example.com"))
            .instruction(instruction).rhythm(rhythm).nextDue(rhythm.firstDue(NOW))
            .createdAtEpochMs(NOW.toInstant().toEpochMilli()).build();
    }

    private static ErrandReport report(String answer) {
        return new ErrandReport(errand("Tell me only if a machine has operating system updates."), answer);
    }

    /** Notify only on trouble: the one word Marvin says when a watch found nothing keeps the inbox quiet. */
    @Test
    void theOneWordForNothingToReportIsSilent() {
        assertThat(report(ErrandReport.NOTHING_TO_REPORT).isSilent()).isTrue();
        assertThat(report("  " + ErrandReport.NOTHING_TO_REPORT + "\n").isSilent()).isTrue();
        assertThat(report("").isSilent()).isTrue();
        assertThat(report("   ").isSilent()).isTrue();
        assertThat(report(null).isSilent()).isTrue();
        assertThat(report("colina27 has 3 updates.").isSilent()).isFalse();
    }

    @Test
    void theSubjectSaysWhichErrandThisWas() {
        assertThat(report("anything").subject())
            .isEqualTo("Marvin: Tell me only if a machine has operating system updates.");
    }

    /** A subject line is a subject line; an instruction long enough to be a paragraph is cut. */
    @Test
    void aLongInstructionIsCutInTheSubject() {
        String long0ne = "Go and look at every machine in the entire fleet and tell me absolutely everything "
            + "you can possibly find out about all of them";
        String subject = new ErrandReport(errand(long0ne), "anything").subject();

        assertThat(subject).startsWith("Marvin: Go and look at every machine");
        assertThat(subject.length()).isLessThanOrEqualTo("Marvin: ".length() + 61);
        assertThat(subject).endsWith("…");
    }

    /**
     * The mail is all the operator gets — nobody was watching — so it carries the answer as Marvin wrote it,
     * then says what this errand is and how to be rid of it.
     */
    @Test
    void theBodyIsTheAnswer_thenWhatThisErrandIsAndHowToCancelIt() {
        String body = report("Colina 27 has 3 updates waiting.").body();

        assertThat(body).startsWith("Colina 27 has 3 updates waiting.\n\n");
        assertThat(body).contains("Marvin runs this errand every day at 08:00.");
        assertThat(body).contains("Cancel it from the Marvin menu in Chat.");
        assertThat(body).endsWith("\n");
        // A sign-off in the spirit of the bundle mail: Marvin, and one line of Marvin about it.
        assertThat(body).contains("\nMarvin\n(");
    }

    /** The report lands in the thread too, so the next question knows what Marvin found while away. */
    @Test
    void itBecomesATurnInTheOperatorsOwnConversation() {
        ConversationTurn turn = report("Colina 27 has 3 updates waiting.").conversationTurn();

        assertThat(turn.role()).isEqualTo(Role.VAIER);
        assertThat(turn.text()).isEqualTo("Errand, Every day at 08:00: Colina 27 has 3 updates waiting.");
    }

    /** What the pane says about the last run, in two words the operator can read. */
    @Test
    void theOutcomeSaysWhetherThereWasAnythingToSay() {
        assertThat(report("Colina 27 has 3 updates waiting.").outcome()).isEqualTo(ErrandReport.REPORTED);
        assertThat(report(ErrandReport.NOTHING_TO_REPORT).outcome()).isEqualTo(ErrandReport.NOTHING_SAID);
        assertThat(ErrandReport.REPORTED).isEqualTo("reported");
        assertThat(ErrandReport.NOTHING_SAID).isEqualTo("nothing to report");
    }

    /**
     * All three words an operator can ever read about a last run live here, including the one no report is
     * ever made of: a run that could not be made at all. Split across two layers, one of them gets renamed
     * alone and the dialog starts saying a word nothing else knows.
     */
    @Test
    void aRunThatCouldNotBeMadeHasItsWordHereToo() {
        assertThat(ErrandReport.FAILED).isEqualTo("failed");
    }

    /** The answer is trimmed: a leading blank line in the mail would look like a broken mail. */
    @Test
    void theAnswerIsTrimmedIntoTheMail() {
        assertThat(report("\n  Colina 27 has 3 updates waiting.  \n").body())
            .startsWith("Colina 27 has 3 updates waiting.\n\n");
    }
}
