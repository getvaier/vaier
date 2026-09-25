package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Every <b>Mailed confirmation</b> still waiting for a yes, and every decision about them: which token
 * opens which, for whom, until when, and how many may wait at once.
 */
public record MailedConfirmations(List<MailedConfirmation> held) {

    /** A roof per operator, so a watch that finds ten things mails three links, not ten. */
    public static final int MOST_WAITING = 3;

    /** One answer for every reason a link does not open, so the page says nothing about which it was. */
    public static final String GONE = "This link has been used, has expired, or was not mailed to you.";

    public MailedConfirmations {
        held = held == null ? List.of() : List.copyOf(held);
    }

    public static MailedConfirmations empty() {
        return new MailedConfirmations(List.of());
    }

    /** One more, with the expired ones swept out; refused once the operator already has enough waiting. */
    public MailedConfirmations with(MailedConfirmation confirmation, long nowEpochMs) {
        List<MailedConfirmation> live = new ArrayList<>(held.stream().filter(c -> !c.expired(nowEpochMs)).toList());
        long waiting = live.stream().filter(c -> c.operator().equals(confirmation.operator())).count();
        if (waiting >= MOST_WAITING) {
            throw new IllegalArgumentException(MOST_WAITING + " proposals are already waiting for the operator's "
                + "yes; nothing more is mailed until they answer.");
        }
        live.add(confirmation);
        return new MailedConfirmations(live);
    }

    /** The live confirmation this token opens for this operator; anything else is {@link #GONE}. */
    public MailedConfirmation open(String token, Operator operator, long nowEpochMs) {
        MailedConfirmation found = null;
        // Every entry is compared, found or not, so the time taken does not depend on which one matched.
        for (MailedConfirmation confirmation : held) {
            if (confirmation.opensWith(token)) {
                found = confirmation;
            }
        }
        if (found == null || found.expired(nowEpochMs) || !found.operator().equals(operator)) {
            throw new NotFoundException(GONE);
        }
        return found;
    }

    public MailedConfirmations without(MailedConfirmation confirmation) {
        return new MailedConfirmations(held.stream().filter(c -> !c.equals(confirmation)).toList());
    }
}
