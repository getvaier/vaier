package net.vaier.domain;

import net.vaier.domain.ConversationTurn.Role;

/**
 * What comes of an <b>errand</b> Marvin has run (#360): the mail to the operator who sent him, the turn in
 * their conversation, and the decision that keeps a watch from becoming noise.
 *
 * <p>That decision is {@link #isSilent}: an errand that says "tell me only if something is wrong" is answered
 * with one word when nothing is, and one word is no mail, no turn and no nudge. Vaier notifies only on
 * trouble; an errand that mailed every morning to say all was well would be deleted within a week, and the
 * one morning it mattered would be skimmed with the rest.
 */
public record ErrandReport(Errand errand, String answer) {

    /** The one word Marvin says when a watch found nothing. Nothing else is said, and nothing is sent. */
    public static final String NOTHING_TO_REPORT = "NOTHING_TO_REPORT";

    /**
     * Every word an operator can read about a last run lives here, the failure included — even though no
     * report is ever made of a run that could not be made. Split across two layers, one of them gets renamed
     * on its own and the dialog starts saying a word nothing else knows.
     */
    public static final String REPORTED = "reported";
    public static final String NOTHING_SAID = "nothing to report";
    public static final String FAILED = "failed";

    /** A subject line, not a paragraph: an instruction longer than this is cut. */
    private static final int SUBJECT_CHARS = 60;

    /** Trimmed once, here, so the mail never opens on a blank line and the word is recognised. */
    private String said() {
        return answer == null ? "" : answer.trim();
    }

    public boolean isSilent() {
        return said().isEmpty() || said().equals(NOTHING_TO_REPORT);
    }

    public String subject() {
        String instruction = errand.instruction();
        String shown = instruction.length() <= SUBJECT_CHARS
            ? instruction
            : instruction.substring(0, SUBJECT_CHARS).trim() + "…";
        return "Marvin: " + shown;
    }

    /**
     * The whole mail. Nobody was watching, so this is all the operator gets: the answer exactly as Marvin
     * wrote it, then what this errand is and how to be rid of it — because a mail that cannot be stopped from
     * inside itself is a mail that gets a filter rule instead.
     */
    public String body() {
        return said() + "\n\n"
            + "Marvin runs this errand " + errand.rhythm().inSentence()
            + ". Cancel it from the Marvin menu in Chat.\n\n"
            + "Marvin\n(who was not doing anything else anyway, as you well know)\n";
    }

    /** The report in the thread, so the next question knows what Marvin found while nobody was looking. */
    public ConversationTurn conversationTurn() {
        return new ConversationTurn(Role.VAIER, "Errand, " + errand.rhythm().describe() + ": " + said());
    }

    /** What the pane says about the last run, in words the operator reads rather than a status code. */
    public String outcome() {
        return isSilent() ? NOTHING_SAID : REPORTED;
    }
}
