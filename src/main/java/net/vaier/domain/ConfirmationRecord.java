package net.vaier.domain;

import java.util.Optional;

/**
 * A <b>Confirmation record</b>: what became of a card, kept as Vaier's turn in the <b>Conversation</b>. The model
 * reads it as {@link #text()}, marked as Vaier's record; the pane draws it as the answered card, never with what
 * came back. {@code result} and {@code cameBack} may be absent.
 */
public record ConfirmationRecord(ActionWording proposed, Outcome outcome, ActionWording result, String cameBack) {

    public enum Outcome { DONE, NOT_DONE, DECLINED }

    // Named as Vaier's record, not as prose: the model once copied a bare "Proposed: …" line instead of acting.
    private static final String SAID = "Card from an action tool (Vaier's record, not your words): ";
    private static final String OLD_RECORD = "Card from an action tool: ";
    private static final String OLDEST_RECORD = "Proposed: ";
    private static final String DECLINED = " (the operator declined)";
    private static final String DONE = " (done: ";
    private static final String NOT_DONE = " (could not be done: ";
    private static final String CAME_BACK = "\n\nWhat came back: ";

    public ConfirmationRecord {
        if (proposed == null || outcome == null) {
            throw new IllegalArgumentException("A confirmation record says what was proposed and what became of it");
        }
    }

    public static ConfirmationRecord declined(ActionWording proposed) {
        return new ConfirmationRecord(proposed, Outcome.DECLINED, new ActionWording("Not done.", null), null);
    }

    /** Whether it records a yes, which is owed a follow-up; "Not now" is owed nothing. */
    public boolean recordsAYes() {
        return outcome != Outcome.DECLINED;
    }

    /** What the model reads, came-back body and all. */
    public String text() {
        String said = SAID + proposed.sentence();
        return switch (outcome) {
            case DECLINED -> said + DECLINED;
            case DONE, NOT_DONE -> said + (outcome == Outcome.DONE ? DONE : NOT_DONE)
                + (result == null ? "" : result.sentence()) + ")" + (cameBack == null ? "" : CAME_BACK + cameBack);
        };
    }

    /**
     * A record kept as plain text before records had a shape: the headline alone, cut before its details, and
     * the outcome. Anything else is not a record.
     */
    public static Optional<ConfirmationRecord> fromText(String text) {
        String rest;
        if (text.startsWith(OLD_RECORD)) {
            rest = text.substring(OLD_RECORD.length());
        } else if (text.startsWith(OLDEST_RECORD)) {
            rest = text.substring(OLDEST_RECORD.length());
        } else {
            return Optional.empty();
        }
        Outcome outcome;
        if (rest.endsWith(DECLINED)) {
            outcome = Outcome.DECLINED;
        } else if (rest.contains(DONE)) {
            outcome = Outcome.DONE;
        } else if (rest.contains(NOT_DONE)) {
            outcome = Outcome.NOT_DONE;
        } else {
            return Optional.empty();
        }
        return Optional.of(new ConfirmationRecord(new ActionWording(headlineOf(rest), null), outcome, null, null));
    }

    private static String headlineOf(String rest) {
        int end = rest.length();
        for (String cut : new String[] { " Sends ", " (" }) {
            int at = rest.indexOf(cut);
            if (at >= 0) {
                end = Math.min(end, at);
            }
        }
        int sentence = rest.indexOf(". ");
        if (sentence >= 0) {
            end = Math.min(end, sentence + 1);
        }
        return rest.substring(0, end).strip();
    }
}
