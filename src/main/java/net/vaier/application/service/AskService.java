package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.AskUseCase;
import net.vaier.application.ForgetConversationUseCase;
import net.vaier.application.GetConversationUseCase;
import net.vaier.application.IsAskAvailableUseCase;
import net.vaier.application.ProposeActionUseCase;
import net.vaier.application.RememberActionOutcomeUseCase;
import net.vaier.application.TakeActionProposalUseCase;
import net.vaier.domain.ActionProposal;
import net.vaier.domain.AskAction;
import net.vaier.domain.AskAvailability;
import net.vaier.domain.AskPrompt;
import net.vaier.domain.Conversation;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ConversationTurn.Role;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Operator;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForConversing;
import net.vaier.domain.port.ForHoldingActionProposals;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingConversations;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * <b>Ask</b> (#360): the operator's questions about the fleet, answered from the fleet's own facts,
 * proposed as cards when they are verbs, and remembered.
 *
 * <p>It orchestrates and decides nothing. Whether Ask may be offered is {@link AskAvailability}'s decision,
 * what Vaier tells the model is {@link AskPrompt}'s, which reads exist is {@link net.vaier.domain.AskTool}'s,
 * which actions may be proposed is {@link AskAction}'s, whether a card is still live is
 * {@link ActionProposal}'s, when a thread is long enough to shorten is {@link Conversation}'s, and holding
 * the conversation is the {@link ForConversing} adapter's.
 */
@Service
@Slf4j
public class AskService implements AskUseCase, IsAskAvailableUseCase, ProposeActionUseCase,
    TakeActionProposalUseCase, GetConversationUseCase, ForgetConversationUseCase,
    RememberActionOutcomeUseCase {

    private final ForPersistingAppConfiguration configPersistence;
    private final ForConversing forConversing;
    private final ForHoldingActionProposals forHoldingActionProposals;
    private final ForPersistingConversations forPersistingConversations;

    public AskService(ForPersistingAppConfiguration configPersistence, ForConversing forConversing,
                      ForHoldingActionProposals forHoldingActionProposals,
                      ForPersistingConversations forPersistingConversations) {
        this.configPersistence = configPersistence;
        this.forConversing = forConversing;
        this.forHoldingActionProposals = forHoldingActionProposals;
        this.forPersistingConversations = forPersistingConversations;
    }

    @Override
    public boolean isAvailable() {
        return AskAvailability.of(configPersistence.load()).available();
    }

    /**
     * One question, answered against the conversation Vaier keeps for this operator, and then remembered.
     * The answer is streamed as it comes; the question and the whole answer are kept once it is complete.
     * When the thread has grown long, the model is asked — afterwards, with no tools — to shorten the
     * older turns, so the operator waits for nothing but the busy state. A summary that could not be made
     * costs nothing: the full thread is kept and the next question tries again.
     */
    @Override
    public void ask(Operator operator, String question, List<ToolOffer> tools, Consumer<String> onText) {
        // Re-decided on every question, never remembered from when the pane was opened: the operator may
        // have cleared the key since.
        Optional<VaierConfig> config = configPersistence.load();
        AskAvailability.of(config).requireAvailable();
        VaierConfig configured = config.orElseThrow();

        Conversation conversation = get(operator);
        log.info("Ask: answering a question with {} tools offered", tools.size());
        StringBuilder answer = new StringBuilder();
        forConversing.converse(configured.getAnthropicApiKey(),
            AskPrompt.forFleet(configured.getDomain()).text(),
            conversation.forModel(), question, tools, text -> {
                answer.append(text);
                onText.accept(text);
            });

        conversation = conversation.withExchange(question, answer.toString());
        forPersistingConversations.save(conversation);

        if (conversation.needsCompaction()) {
            compact(configured.getAnthropicApiKey(), conversation);
        }
    }

    private void compact(String apiKey, Conversation conversation) {
        StringBuilder summary = new StringBuilder();
        try {
            forConversing.converse(apiKey, AskPrompt.forCompaction().text(), conversation.olderTurns(),
                "Summarise the conversation above.", List.of(), summary::append);
        } catch (RuntimeException e) {
            log.warn("Ask could not shorten a long conversation; keeping it whole: {}", e.toString());
            return;
        }
        Conversation compacted = conversation.compacted(summary.toString());
        if (compacted != conversation) {
            forPersistingConversations.save(compacted);
        }
    }

    @Override
    public Conversation get(Operator operator) {
        return forPersistingConversations.load(operator).orElseGet(() -> Conversation.empty(operator));
    }

    @Override
    public void forget(Operator operator) {
        forPersistingConversations.forget(operator);
    }

    @Override
    public void remember(Operator operator, String outcome) {
        forPersistingConversations.save(get(operator).with(new ConversationTurn(Role.VAIER, outcome)));
    }

    /** A card, held. Whether it may be proposed at all is {@link ActionProposal}'s decision. */
    @Override
    public ActionProposal propose(AskAction action, Map<String, String> arguments) {
        ActionProposal proposal = ActionProposal.propose(action, arguments, System.currentTimeMillis());
        forHoldingActionProposals.hold(proposal);
        return proposal;
    }

    /** The clicked card, once; gone or expired is refused before anything could run on it. */
    @Override
    public ActionProposal take(String id) {
        return forHoldingActionProposals.take(id)
            .orElseThrow(() -> new NotFoundException("That card is gone; ask again."))
            .requireLive(System.currentTimeMillis());
    }
}
