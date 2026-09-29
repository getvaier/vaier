package net.vaier.application;

import net.vaier.domain.ActionProposal;
import net.vaier.domain.ChatAction;
import net.vaier.domain.Operator;

import java.util.Map;

/**
 * Hold one proposed <b>Chat action</b> as a <b>Confirmation</b> waiting for this operator's click (#360
 * slice 2). Nothing runs here. Throws {@code IllegalArgumentException} when the action lacks what it needs.
 */
public interface ProposeActionUseCase {

    ActionProposal propose(Operator operator, ChatAction action, Map<String, String> arguments);
}
