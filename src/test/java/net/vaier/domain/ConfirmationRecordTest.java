package net.vaier.domain;

import net.vaier.domain.ConfirmationRecord.Outcome;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** A <b>Confirmation record</b>: what became of a card, kept for the model as text and for the pane as a card. */
class ConfirmationRecordTest {

    private static final ActionWording PROPOSED = new ActionWording("Back up Colina 27 now.",
        "With the backup job it already has.");
    private static final String SAID = "Card from an action tool (Vaier's record, not your words): "
        + "Back up Colina 27 now. With the backup job it already has.";

    /** The model reads one shape, marked as Vaier's record; a read's answer rides under it. */
    @Test
    void theModelReadsTheRecordInOneShape() {
        record Row(ConfirmationRecord record, String text) {}
        for (Row row : new Row[] {
            new Row(new ConfirmationRecord(PROPOSED, Outcome.DONE,
                new ActionWording("Backing up.", "See the Backups pane."), null),
                SAID + " (done: Backing up. See the Backups pane.)"),
            new Row(new ConfirmationRecord(PROPOSED, Outcome.NOT_DONE,
                new ActionWording("Vaier could not do that.", null), null),
                SAID + " (could not be done: Vaier could not do that.)"),
            new Row(new ConfirmationRecord(PROPOSED, Outcome.DONE, new ActionWording("Done.", null),
                "openhab answered 200.\n\nOFF"),
                SAID + " (done: Done.)\n\nWhat came back: openhab answered 200.\n\nOFF"),
            new Row(ConfirmationRecord.declined(PROPOSED), SAID + " (the operator declined)"),
        }) {
            assertThat(row.record().text()).as(row.text()).isEqualTo(row.text());
        }
        assertThat(ConfirmationRecord.declined(PROPOSED).result()).isEqualTo(new ActionWording("Not done.", null));
    }

    /**
     * Records kept as plain text before they had a shape still read as an answered card: the headline alone,
     * cut before the details, and no outcome wording to show.
     */
    @Test
    void anOldTextRecordReadsAsACollapsedCard() {
        record Row(String text, Optional<ConfirmationRecord> read) {}
        for (Row row : new Row[] {
            new Row("Card from an action tool: Read the battery levels at Colina 27 Sends GET /rest/items "
                + "to openHAB. (done: Done — openHAB answered 200.)\n\nWhat came back: [{\"name\":\"x\"}]",
                Optional.of(new ConfirmationRecord(new ActionWording("Read the battery levels at Colina 27", null),
                    Outcome.DONE, null, null))),
            new Row("Card from an action tool: Back up Colina 27 now. With the backup job it already has. "
                + "(could not be done: Vaier could not do that.)",
                Optional.of(new ConfirmationRecord(new ActionWording("Back up Colina 27 now.", null),
                    Outcome.NOT_DONE, null, null))),
            // The older shape, before records were named as Vaier's.
            new Row("Proposed: Let 203.0.113.9 reach your services again. (the operator declined)",
                Optional.of(new ConfirmationRecord(new ActionWording("Let 203.0.113.9 reach your services again.",
                    null), Outcome.DECLINED, null, null))),
            new Row("Proposed: Back up Colina 27 now. (done: Backing up Colina 27 now.)",
                Optional.of(new ConfirmationRecord(new ActionWording("Back up Colina 27 now.", null),
                    Outcome.DONE, null, null))),
            new Row("Colina 27 is green.", Optional.empty()),
            new Row("Card from an action tool: something with no outcome said", Optional.empty()),
        }) {
            assertThat(ConfirmationRecord.fromText(row.text())).as(row.text()).isEqualTo(row.read());
        }
    }
}
