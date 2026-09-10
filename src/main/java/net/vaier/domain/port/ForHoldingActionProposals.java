package net.vaier.domain.port;

import net.vaier.domain.ActionProposal;

import java.util.Optional;

/**
 * Driven port holding the <b>Confirmation</b>s waiting for a click (#360 slice 2). Ephemeral, like a
 * session: a card outlives neither its ten minutes nor the process.
 */
public interface ForHoldingActionProposals {

    void hold(ActionProposal proposal);

    /** The proposal with that id, removed as it is handed over, so it is taken once; empty when gone. */
    Optional<ActionProposal> take(String id);
}
