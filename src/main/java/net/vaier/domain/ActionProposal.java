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
public record ActionProposal(String id, ChatAction action, Map<String, String> arguments, ActionWording wording,
                             long proposedAtEpochMs) {

    static final String PROPOSED = "Proposed to the operator as a card: ";

    /**
     * What the model is told in place of a question when a yes is followed up. Vaier's words, not the
     * operator's; it steers clear of talking about "the card", which reads as a phantom confirmation.
     */
    public static final String FOLLOW_UP = "(Vaier, not the operator.) The operator just said yes, and Vaier's "
        + "record above says what became of it and, for a read, what came back. Carry on as if you had never "
        + "stopped: answer what they asked from what came back, or say in a sentence or two what it means and "
        + "whether anything comes next. When what came back does not hold the answer, say so plainly; never "
        + "fill the gap. Be brief, do not repeat the record, and do not ask for the yes again.";

    public static final Duration TTL = Duration.ofMinutes(10);

    public static ActionProposal propose(ChatAction action, Map<String, String> arguments, long nowEpochMs) {
        for (ToolParameter parameter : action.parameters()) {
            if (parameter.optional()) {
                continue;
            }
            String value = arguments.get(parameter.name());
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Say which " + parameter.name() + ".");
            }
        }
        return new ActionProposal(UUID.randomUUID().toString(), action, Map.copyOf(arguments),
            action.wording(arguments), nowEpochMs);
    }

    /** The call a yes runs, when this is a service call. */
    public ServiceCall serviceCall() {
        if (action != ChatAction.CALL_SERVICE) {
            throw new IllegalArgumentException("That is not a service call.");
        }
        return ServiceCall.proposed(arguments.get("method"), arguments.get("path"), arguments.get("body"));
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

    /** What Vaier remembers of the card once a yes has run. */
    public ConfirmationRecord record(boolean done, ActionWording outcome, String cameBack) {
        return new ConfirmationRecord(wording, done ? ConfirmationRecord.Outcome.DONE : ConfirmationRecord.Outcome.NOT_DONE,
            outcome, cameBack);
    }

    public ConfirmationRecord declined() {
        return ConfirmationRecord.declined(wording);
    }

    /** What the model is told. The one lie this must prevent is "done". */
    public String toolResult() {
        return PROPOSED + "\"" + wording.sentence() + "\" Nothing has happened yet, and nothing "
            + "will until they click it. Tell them it is waiting for their click, and do not say it is done.";
    }
}
