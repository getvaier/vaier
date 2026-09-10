package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ChatUseCase;
import net.vaier.application.ForgetConversationUseCase;
import net.vaier.application.ForgetUseCase;
import net.vaier.application.GetConversationUseCase;
import net.vaier.application.GetMemoryUseCase;
import net.vaier.application.GetSpendUseCase;
import net.vaier.application.IsChatAvailableUseCase;
import net.vaier.application.ProposeActionUseCase;
import net.vaier.application.RememberActionOutcomeUseCase;
import net.vaier.application.RememberUseCase;
import net.vaier.application.TakeActionProposalUseCase;
import net.vaier.domain.ActionProposal;
import net.vaier.domain.ChatAction;
import net.vaier.domain.ChatAvailability;
import net.vaier.domain.ChatPrompt;
import net.vaier.domain.Conversation;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ConversationTurn.Role;
import net.vaier.domain.Memory;
import net.vaier.domain.ModelUsage;
import net.vaier.domain.MonthSpend;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Operator;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForConversing;
import net.vaier.domain.port.ForHoldingActionProposals;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingConversations;
import net.vaier.domain.port.ForPersistingMemory;
import net.vaier.domain.port.ForPersistingSpend;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * <b>Chat</b> (#360): the operator's questions about the fleet, answered from the fleet's own facts,
 * proposed as cards when they are verbs, and remembered.
 *
 * <p>It orchestrates and decides nothing. Whether Chat may be offered is {@link ChatAvailability}'s decision,
 * what Vaier tells the model is {@link ChatPrompt}'s, which reads exist is {@link net.vaier.domain.ChatTool}'s,
 * which actions may be proposed is {@link ChatAction}'s, whether a card is still live is
 * {@link ActionProposal}'s, when a thread is long enough to shorten is {@link Conversation}'s, and holding
 * the conversation is the {@link ForConversing} adapter's.
 */
@Service
@Slf4j
public class ChatService implements ChatUseCase, IsChatAvailableUseCase, ProposeActionUseCase,
    TakeActionProposalUseCase, GetConversationUseCase, ForgetConversationUseCase,
    RememberActionOutcomeUseCase, RememberUseCase, ForgetUseCase, GetMemoryUseCase, GetSpendUseCase {

    private final ForPersistingAppConfiguration configPersistence;
    private final ForConversing forConversing;
    private final ForHoldingActionProposals forHoldingActionProposals;
    private final ForPersistingConversations forPersistingConversations;
    private final ForPersistingMemory forPersistingMemory;
    private final ForPersistingSpend forPersistingSpend;

    public ChatService(ForPersistingAppConfiguration configPersistence, ForConversing forConversing,
                      ForHoldingActionProposals forHoldingActionProposals,
                      ForPersistingConversations forPersistingConversations,
                      ForPersistingMemory forPersistingMemory,
                      ForPersistingSpend forPersistingSpend) {
        this.configPersistence = configPersistence;
        this.forConversing = forConversing;
        this.forHoldingActionProposals = forHoldingActionProposals;
        this.forPersistingConversations = forPersistingConversations;
        this.forPersistingMemory = forPersistingMemory;
        this.forPersistingSpend = forPersistingSpend;
    }

    @Override
    public MonthSpend thisMonth() {
        return forPersistingSpend.load().month(YearMonth.now());
    }

    /** Counted the moment it is known, under this month. */
    private void count(ModelUsage used) {
        forPersistingSpend.save(forPersistingSpend.load().record(used, YearMonth.now()));
    }

    @Override
    public Memory.Fact remember(String fact) {
        Memory memory = forPersistingMemory.load().remember(fact, System.currentTimeMillis());
        forPersistingMemory.save(memory);
        return memory.facts().get(memory.facts().size() - 1);
    }

    @Override
    public void forget(String id) {
        forPersistingMemory.save(forPersistingMemory.load().forget(id));
    }

    @Override
    public Memory getMemory() {
        return forPersistingMemory.load();
    }

    @Override
    public boolean isAvailable() {
        return ChatAvailability.of(configPersistence.load()).available();
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
        ChatAvailability.of(config).requireAvailable();
        VaierConfig configured = config.orElseThrow();

        Conversation conversation = get(operator);
        log.info("Chat: answering a question with {} tools offered", tools.size());
        StringBuilder answer = new StringBuilder();
        ModelUsage used = forConversing.converse(configured.getAnthropicApiKey(),
            ChatPrompt.forFleet(configured.getDomain(), LocalDate.now(), forPersistingMemory.load()).text(),
            conversation.forModel(), question, tools, text -> {
                answer.append(text);
                onText.accept(text);
            });
        count(used);

        conversation = conversation.withExchange(question, answer.toString());
        forPersistingConversations.save(conversation);

        if (conversation.needsCompaction()) {
            compact(configured.getAnthropicApiKey(), conversation);
        }
    }

    private void compact(String apiKey, Conversation conversation) {
        StringBuilder summary = new StringBuilder();
        try {
            count(forConversing.converse(apiKey, ChatPrompt.forCompaction().text(), conversation.olderTurns(),
                "Summarise the conversation above.", List.of(), summary::append));
        } catch (RuntimeException e) {
            log.warn("Chat could not shorten a long conversation; keeping it whole: {}", e.toString());
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
    public ActionProposal propose(ChatAction action, Map<String, String> arguments) {
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
