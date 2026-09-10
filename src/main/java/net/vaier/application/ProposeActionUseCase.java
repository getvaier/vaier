package net.vaier.application;

import net.vaier.domain.ActionProposal;
import net.vaier.domain.AskAction;

import java.util.Map;

/**
 * Hold one proposed <b>Ask action</b> as a <b>Confirmation</b> waiting for the operator's click (#360
 * slice 2). Nothing runs here. Throws {@code IllegalArgumentException} when the action lacks what it needs.
 */
public interface ProposeActionUseCase {

    ActionProposal propose(AskAction action, Map<String, String> arguments);
}
