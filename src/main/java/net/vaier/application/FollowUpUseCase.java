package net.vaier.application;

import net.vaier.domain.ChatUnavailableException;
import net.vaier.domain.Operator;
import net.vaier.domain.ToolOffer;

import java.util.List;
import java.util.function.Consumer;

/**
 * Let <b>Marvin</b> carry on after the operator's yes to a <b>Confirmation</b> — a <b>follow-up</b> — with no
 * question typed: what came of the card is already in the kept <b>Conversation</b>.
 */
public interface FollowUpUseCase {

    /**
     * Stream the follow-up to {@code onText} and keep it. Does nothing when no yes is waiting for one.
     *
     * @return whether one was owed, so silence from an owed follow-up can be told from nothing to say.
     * @throws ChatUnavailableException when no <b>Anthropic API key</b> is stored.
     */
    boolean followUp(Operator operator, List<ToolOffer> tools, Consumer<String> onText);
}
