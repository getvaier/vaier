package net.vaier.domain.port;

import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ModelUsage;
import net.vaier.domain.ToolOffer;

import java.util.List;
import java.util.function.Consumer;

/**
 * Driven port for holding one turn of a <b>Conversation</b> with the Claude API (#360).
 *
 * <p>Deliberately plain: a question, what has been said so far, the tools that may be read, and a consumer
 * the answer arrives through in pieces. No streaming library and no model type crosses this boundary — the
 * one adapter behind it owns every one of those, so what Vaier is integrated with can change without the
 * domain hearing about it.
 */
public interface ForConversing {

    /**
     * Chat, and hand each piece of the answer to {@code onText} as it arrives. Blocks until the answer is
     * complete.
     *
     * @param apiKey       the operator's own <b>Anthropic API key</b>; never logged, never echoed back.
     * @param systemPrompt what Vaier tells the model before the question — see {@code ChatPrompt}.
     * @param history      the conversation so far, oldest first.
     * @param question     what the operator just asked.
     * @param tools        the reads the model may make while answering.
     * @param onText       receives the answer in pieces, in order.
     * @return the tokens the answer used, summed over every call it took — so it can be counted.
     * @throws IllegalArgumentException when the conversation could not be held — a rejected key, an
     *                                  unreachable API — carrying a sentence the operator can act on and
     *                                  never the key itself.
     */
    ModelUsage converse(String apiKey, String systemPrompt, List<ConversationTurn> history, String question,
                        List<ToolOffer> tools, Consumer<String> onText);
}
