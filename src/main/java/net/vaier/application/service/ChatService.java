package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.AddErrandUseCase;
import net.vaier.application.CancelErrandUseCase;
import net.vaier.application.ChatUseCase;
import net.vaier.application.ForgetConversationUseCase;
import net.vaier.application.ForgetUseCase;
import net.vaier.application.GetConversationUseCase;
import net.vaier.application.GetDueErrandsUseCase;
import net.vaier.application.GetErrandsUseCase;
import net.vaier.application.GetMemoryUseCase;
import net.vaier.application.GetSpendUseCase;
import net.vaier.application.IsChatAvailableUseCase;
import net.vaier.application.ProposeActionUseCase;
import net.vaier.application.RememberActionOutcomeUseCase;
import net.vaier.application.ReadWebPageUseCase;
import net.vaier.application.RememberUseCase;
import net.vaier.application.RunErrandUseCase;
import net.vaier.application.SearchWebUseCase;
import net.vaier.application.TakeActionProposalUseCase;
import net.vaier.domain.ActionProposal;
import net.vaier.domain.ChatAction;
import net.vaier.domain.ChatAvailability;
import net.vaier.domain.ChatPrompt;
import net.vaier.domain.Conversation;
import net.vaier.domain.Errand;
import net.vaier.domain.ErrandReport;
import net.vaier.domain.Errands;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ConversationTurn.Role;
import net.vaier.domain.Memory;
import net.vaier.domain.ModelUsage;
import net.vaier.domain.MonthSpend;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Operator;
import net.vaier.domain.Rhythm;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.WebAddress;
import net.vaier.domain.WebPage;
import net.vaier.domain.WebQuery;
import net.vaier.domain.WebSearchResults;
import net.vaier.domain.port.ForConversing;
import net.vaier.domain.port.ForHoldingActionProposals;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingConversations;
import net.vaier.domain.port.ForPersistingErrands;
import net.vaier.domain.port.ForPersistingMemory;
import net.vaier.domain.port.ForPersistingSpend;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForReadingWebPages;
import net.vaier.domain.port.ForSendingAdminNotification;
import net.vaier.domain.port.ForSearchingTheWeb;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZonedDateTime;
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
 * {@link ActionProposal}'s, when a thread is long enough to shorten is {@link Conversation}'s, which addresses
 * are on the public internet is {@link WebAddress}'s, and holding the conversation is the
 * {@link ForConversing} adapter's.
 *
 * <p>Since the <b>errand</b> it also runs questions nobody asked: an errand's own run, with no history, the
 * errand's prompt, and a mail at the end unless {@link ErrandReport} says there was nothing to say. Whether
 * an errand is due, what it becomes afterwards and whether the report is worth sending are all the domain's
 * decisions; the clock is the one thing this service reads and hands in.
 */
@Service
@Slf4j
public class ChatService implements ChatUseCase, IsChatAvailableUseCase, ProposeActionUseCase,
    TakeActionProposalUseCase, GetConversationUseCase, ForgetConversationUseCase,
    RememberActionOutcomeUseCase, RememberUseCase, ForgetUseCase, GetMemoryUseCase, GetSpendUseCase,
    ReadWebPageUseCase, SearchWebUseCase, AddErrandUseCase, CancelErrandUseCase, GetErrandsUseCase,
    GetDueErrandsUseCase, RunErrandUseCase {

    /** The SSE topic and event the Chat pane listens on when an errand has reported while nobody asked. */
    static final String CHAT_TOPIC = "chat";
    static final String ERRAND_REPORTED_EVENT = "errand-reported";

    private final ForPersistingAppConfiguration configPersistence;
    private final ForConversing forConversing;
    private final ForHoldingActionProposals forHoldingActionProposals;
    private final ForPersistingConversations forPersistingConversations;
    private final ForPersistingMemory forPersistingMemory;
    private final ForPersistingSpend forPersistingSpend;
    private final ForReadingWebPages forReadingWebPages;
    private final ForSearchingTheWeb forSearchingTheWeb;
    private final ForPersistingErrands forPersistingErrands;
    private final ForSendingAdminNotification forSendingAdminNotification;
    private final ForPublishingEvents forPublishingEvents;
    private final Clock clock;

    public ChatService(ForPersistingAppConfiguration configPersistence, ForConversing forConversing,
                      ForHoldingActionProposals forHoldingActionProposals,
                      ForPersistingConversations forPersistingConversations,
                      ForPersistingMemory forPersistingMemory,
                      ForPersistingSpend forPersistingSpend,
                      ForReadingWebPages forReadingWebPages,
                      ForSearchingTheWeb forSearchingTheWeb,
                      ForPersistingErrands forPersistingErrands,
                      ForSendingAdminNotification forSendingAdminNotification,
                      ForPublishingEvents forPublishingEvents,
                      Clock clock) {
        this.configPersistence = configPersistence;
        this.forConversing = forConversing;
        this.forHoldingActionProposals = forHoldingActionProposals;
        this.forPersistingConversations = forPersistingConversations;
        this.forPersistingMemory = forPersistingMemory;
        this.forPersistingSpend = forPersistingSpend;
        this.forReadingWebPages = forReadingWebPages;
        this.forSearchingTheWeb = forSearchingTheWeb;
        this.forPersistingErrands = forPersistingErrands;
        this.forSendingAdminNotification = forSendingAdminNotification;
        this.forPublishingEvents = forPublishingEvents;
        this.clock = clock;
    }

    /** The one clock in Chat, read here and handed to the domain, which never looks at one. */
    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }

    /** The domain judges the address and asks the port; the service only hands the port in. */
    @Override
    public WebPage read(String url) {
        return WebAddress.of(url).read(forReadingWebPages);
    }

    @Override
    public WebSearchResults search(String query) {
        return WebQuery.of(query).search(forSearchingTheWeb);
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
            ChatPrompt.forFleet(configured.getDomain(), now(), forPersistingMemory.load(),
                forPersistingErrands.load(), operator).text(),
            conversation.forModel(), question, tools, text -> {
                answer.append(text);
                onText.accept(text);
            });
        count(used);

        Conversation kept = appendExchange(operator, question, answer.toString());

        if (kept.needsCompaction()) {
            compact(configured.getAnthropicApiKey(), kept);
        }
    }

    /**
     * Every change to a kept conversation goes through one of these two, and both re-read before they write.
     * An answer takes tens of seconds and an errand can report in the middle of one: saving the instance the
     * question loaded would throw that turn away without a trace. {@code synchronized} because two writers
     * reading the same file at the same moment is the same bug with better timing.
     */
    private synchronized Conversation appendExchange(Operator operator, String question, String answer) {
        Conversation conversation = get(operator).withExchange(question, answer);
        forPersistingConversations.save(conversation);
        return conversation;
    }

    private synchronized void appendTurn(Operator operator, ConversationTurn turn) {
        forPersistingConversations.save(get(operator).with(turn));
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
        appendTurn(operator, new ConversationTurn(Role.VAIER, outcome));
    }

    // --- errands: what Marvin is sent off to do later (#360 slice 2) ---------------------------------

    /** The rhythm is read by the domain, which also works out when the errand first falls due. */
    @Override
    public synchronized Errand add(Operator operator, String instruction, String rhythm) {
        Errands errands = forPersistingErrands.load()
            .add(operator, instruction, Rhythm.parse(rhythm), now());
        forPersistingErrands.save(errands);
        return errands.errands().get(errands.errands().size() - 1);
    }

    @Override
    public synchronized void cancel(Operator operator, String id) {
        forPersistingErrands.save(forPersistingErrands.load().cancel(operator, id));
    }

    @Override
    public List<Errand> getErrands(Operator operator) {
        return forPersistingErrands.load().of(operator);
    }

    @Override
    public List<Errand> due() {
        return forPersistingErrands.load().due(clock.instant());
    }

    /**
     * One errand, run with nobody watching. It starts fresh — no history, because the thread is the
     * operator's and an errand is not part of it; what carries across runs is Memory, and that is in the
     * prompt. The answer is mailed unless the report says there was nothing to say, kept as a turn so the
     * next question knows what was found, and the pane is nudged so it appears without being asked for.
     *
     * <p>An answer that could not be made moves the errand on all the same, marked "failed". Nobody is
     * watching: a retry here would be a retry storm against a paid API that nothing stops.
     */
    @Override
    public void run(Errand errand, List<ToolOffer> tools) {
        Optional<VaierConfig> config = configPersistence.load();
        ChatAvailability.of(config).requireAvailable();
        VaierConfig configured = config.orElseThrow();

        ZonedDateTime now = now();
        String outcome;
        try {
            StringBuilder answer = new StringBuilder();
            count(forConversing.converse(configured.getAnthropicApiKey(),
                ChatPrompt.forErrand(configured.getDomain(), now, forPersistingMemory.load(), errand).text(),
                List.of(), errand.instruction(), tools, answer::append));
            outcome = report(errand, answer.toString());
        } catch (RuntimeException e) {
            log.warn("Marvin could not run the errand {}: {}", errand.id(), e.toString());
            outcome = ErrandReport.FAILED;
        }
        afterRun(errand.id(), now, outcome);
    }

    /**
     * The errand, moved on. Re-read and {@code synchronized} for the same reason every conversation mutation
     * is: a run takes tens of seconds, and an errand added or cancelled while it ran must survive it.
     */
    private synchronized void afterRun(String id, ZonedDateTime now, String outcome) {
        forPersistingErrands.save(forPersistingErrands.load().afterRun(id, now, outcome));
    }

    /** Whether any of this is sent at all is {@link ErrandReport}'s decision, not this method's. */
    private String report(Errand errand, String answer) {
        ErrandReport report = new ErrandReport(errand, answer);
        if (!report.isSilent()) {
            errand.operator().email().ifPresent(to -> forSendingAdminNotification
                .sendTo(to, report.subject(), report.body(), "errand " + errand.id()));
            appendTurn(errand.operator(), report.conversationTurn());
            forPublishingEvents.publish(CHAT_TOPIC, ERRAND_REPORTED_EVENT, "");
        }
        return report.outcome();
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
