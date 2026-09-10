package net.vaier.domain;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * A <b>Confirmation</b> as Vaier holds it (#360 slice 2): one proposed action waiting for the operator's
 * click. It lives ten minutes and is taken once, so a card cannot be clicked twice and a stale card cannot
 * run something the operator has forgotten proposing.
 *
 * <p>The arguments are the canonical ones — the machine's id as well as its name, the phone's name as well
 * as its code — so the click runs against an identity and the card reads in names.
 */
public record ActionProposal(String id, AskAction action, Map<String, String> arguments, String sentence,
                             long proposedAtEpochMs) {

    public static final Duration TTL = Duration.ofMinutes(10);

    public static ActionProposal propose(AskAction action, Map<String, String> arguments, long nowEpochMs) {
        for (ToolParameter parameter : action.parameters()) {
            String value = arguments.get(parameter.name());
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Say which " + parameter.name() + ".");
            }
        }
        return new ActionProposal(UUID.randomUUID().toString(), action, Map.copyOf(arguments),
            action.sentence(arguments), nowEpochMs);
    }

    public boolean expired(long nowEpochMs) {
        return nowEpochMs - proposedAtEpochMs >= TTL.toMillis();
    }

    public ActionProposal requireLive(long nowEpochMs) {
        if (expired(nowEpochMs)) {
            throw new IllegalArgumentException("That card has expired; ask again.");
        }
        return this;
    }

    /** What Vaier remembers of the card once clicked, in the words the next question reads back. */
    public String outcomeSentence(boolean done, String text) {
        return "Proposed: " + sentence + " (" + (done ? "done: " : "could not be done: ") + text + ")";
    }

    public String declinedSentence() {
        return "Proposed: " + sentence + " (the operator declined)";
    }

    /** What the model is told. The one lie this must prevent is "done". */
    public String toolResult() {
        return "Proposed to the operator as a card: \"" + sentence + "\" Nothing has happened yet, and nothing "
            + "will until they click it. Tell them it is waiting for their click, and do not say it is done.";
    }
}
