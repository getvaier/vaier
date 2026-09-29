package net.vaier.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * One turn of a <b>Conversation</b>: who spoke, what they said, and when. A turn kept before turns said when has
 * no {@code at}. A <b>Confirmation record</b> is Vaier's turn carrying its {@code confirmation}; its text is the
 * record's, for the model.
 */
public record ConversationTurn(Role role, String text, Instant at, ConfirmationRecord confirmation) {

    /** Who spoke. Only two things ever do. */
    public enum Role { OPERATOR, VAIER }

    public ConversationTurn {
        if (role == null) {
            throw new IllegalArgumentException("A conversation turn must say who spoke");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A conversation turn must carry words");
        }
    }

    public ConversationTurn(Role role, String text) {
        this(role, text, null, null);
    }

    public static ConversationTurn said(Role role, String text, Instant at) {
        return new ConversationTurn(role, text, at, null);
    }

    public static ConversationTurn recording(ConfirmationRecord confirmation, Instant at) {
        return new ConversationTurn(Role.VAIER, confirmation.text(), at, confirmation);
    }

    /** The card this turn shows as: its record, or an old record kept as Vaier's plain text. */
    public Optional<ConfirmationRecord> card() {
        if (confirmation != null) {
            return Optional.of(confirmation);
        }
        return role == Role.VAIER ? ConfirmationRecord.fromText(text) : Optional.empty();
    }
}
