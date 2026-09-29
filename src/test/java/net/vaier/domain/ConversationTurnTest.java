package net.vaier.domain;

import net.vaier.domain.ConversationTurn.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One turn of a <b>Conversation</b>: who spoke, and what they said (#360). */
class ConversationTurnTest {

    @Test
    void aTurnIsSpokenByTheOperatorOrByVaier() {
        assertThat(new ConversationTurn(Role.OPERATOR, "which machine is red?").role())
            .isEqualTo(Role.OPERATOR);
        assertThat(Role.values()).containsExactly(Role.OPERATOR, Role.VAIER);
    }

    @Test
    void aTurnMustSayWhoSpoke() {
        assertThatThrownBy(() -> new ConversationTurn(null, "hello"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** An empty turn is not a turn; it would reach the model as a blank message and be refused there. */
    @Test
    void aTurnMustCarryWords() {
        assertThatThrownBy(() -> new ConversationTurn(Role.VAIER, "  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A card record is Vaier's turn: the model reads its text, the pane its shape. A turn kept before records
     * had a shape still shows as a card when its words are one; nobody else's words ever do.
     */
    @Test
    void aTurnShowsAsACard_whenItRecordsOne() {
        ConfirmationRecord declined = ConfirmationRecord.declined(new ActionWording("Back up Colina 27 now.", null));
        Instant at = Instant.parse("2026-09-29T08:00:00Z");
        ConversationTurn recorded = ConversationTurn.recording(declined, at);

        assertThat(recorded.role()).isEqualTo(Role.VAIER);
        assertThat(recorded.text()).isEqualTo(declined.text());
        assertThat(recorded.at()).isEqualTo(at);

        String old = "Proposed: Back up Colina 27 now. (the operator declined)";
        record Row(String why, ConversationTurn turn, Optional<ConfirmationRecord> card) {}
        for (Row row : new Row[] {
            new Row("a record", recorded, Optional.of(declined)),
            new Row("an old record", new ConversationTurn(Role.VAIER, old), ConfirmationRecord.fromText(old)),
            new Row("the operator's words", new ConversationTurn(Role.OPERATOR, old), Optional.empty()),
            new Row("Marvin's words", new ConversationTurn(Role.VAIER, "It is green."), Optional.empty()),
        }) {
            assertThat(row.turn().card()).as(row.why()).isEqualTo(row.card());
        }
    }
}
