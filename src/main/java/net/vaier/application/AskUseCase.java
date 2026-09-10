package net.vaier.application;

import net.vaier.domain.AskUnavailableException;
import net.vaier.domain.Operator;
import net.vaier.domain.ToolOffer;

import java.util.List;
import java.util.function.Consumer;

/**
 * Ask Vaier a question about the fleet and be answered from the fleet's own facts (#360 slice 1).
 *
 * <p>Read-only: the tools offered are reads, and there is no way from here to change anything.
 */
public interface AskUseCase {

    /**
     * Hold one turn of the <b>Conversation</b>, handing each piece of the answer to {@code onText} as it
     * arrives. Blocks until the answer is complete.
     *
     * @throws AskUnavailableException when no <b>Anthropic API key</b> is stored.
     */
    void ask(Operator operator, String question, List<ToolOffer> tools, Consumer<String> onText);
}
