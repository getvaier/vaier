package net.vaier.domain.port;

import net.vaier.domain.ActionProposal;
import net.vaier.domain.Operator;

import java.util.List;
import java.util.Optional;

/**
 * Driven port holding the <b>Confirmation</b>s waiting for a click (#360 slice 2), each for the operator it
 * was proposed to. Ephemeral, like a session: a card outlives neither its ten minutes nor the process.
 */
public interface ForHoldingActionProposals {

    void hold(Operator operator, ActionProposal proposal);

    /** The proposal with that id, removed as it is handed over, so it is taken once; empty when gone. */
    Optional<ActionProposal> take(String id);

    /** Every proposal still held for this operator; whether one is live is the proposal's to say. */
    List<ActionProposal> heldFor(Operator operator);
}
