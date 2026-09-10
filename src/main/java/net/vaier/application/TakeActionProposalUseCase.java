package net.vaier.application;

import net.vaier.domain.ActionProposal;

/**
 * Take a <b>Confirmation</b> the operator has clicked, once (#360 slice 2). Throws
 * {@code NotFoundException} when no such card is held and {@code IllegalArgumentException} when it has
 * expired; either way nothing may run.
 */
public interface TakeActionProposalUseCase {

    ActionProposal take(String id);
}
