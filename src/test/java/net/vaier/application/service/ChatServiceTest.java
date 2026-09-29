package net.vaier.application.service;

import net.vaier.domain.ActionProposal;
import net.vaier.domain.ActionWording;
import net.vaier.domain.Bundle;
import net.vaier.domain.ChatAction;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.ChatPrompt;
import net.vaier.domain.ChatTool;
import net.vaier.domain.ChatUnavailableException;
import net.vaier.domain.ConfirmationWatch;
import net.vaier.domain.ConfirmationRecord;
import net.vaier.domain.Conversation;
import net.vaier.domain.Errand;
import net.vaier.domain.ErrandReport;
import net.vaier.domain.Errands;
import net.vaier.domain.MachineId;
import net.vaier.domain.MailedConfirmation;
import net.vaier.domain.MailedConfirmations;
import net.vaier.domain.Operator;
import net.vaier.domain.Rhythm;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.Memory;
import net.vaier.domain.ModelUsage;
import net.vaier.domain.Spend;
import net.vaier.domain.ConversationTurn.Role;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.WebAddress;
import net.vaier.domain.WebPage;
import net.vaier.domain.WebSearchResult;
import net.vaier.domain.WebSearchResults;
import net.vaier.domain.port.ForConversing;
import net.vaier.domain.port.ForPersistingConversations;
import net.vaier.domain.port.ForPersistingErrands;
import net.vaier.domain.port.ForPersistingMailedConfirmations;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSendingAdminNotification;
import net.vaier.domain.port.ForPersistingMemory;
import net.vaier.domain.port.ForPersistingSpend;
import net.vaier.domain.port.ForReadingWebPages;
import net.vaier.domain.port.ForSearchingTheWeb;
import net.vaier.domain.port.ForHoldingActionProposals;
import net.vaier.domain.port.ForHoldingBundles;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock ForPersistingAppConfiguration configPersistence;
    @Mock ForConversing forConversing;
    @Mock ForHoldingActionProposals forHoldingActionProposals;
    @Mock ForHoldingBundles forHoldingBundles;
    @Spy KeptConversations forPersistingConversations = new KeptConversations();
    @Mock ForPersistingMemory forPersistingMemory;
    @Mock ForPersistingSpend forPersistingSpend;
    @Mock ForReadingWebPages forReadingWebPages;
    @Mock ForSearchingTheWeb forSearchingTheWeb;
    @Mock ForPersistingErrands forPersistingErrands;
    @Mock ForSendingAdminNotification forSendingAdminNotification;
    @Mock ForPublishingEvents forPublishingEvents;
    @Mock ForPersistingMailedConfirmations forPersistingMailedConfirmations;

    /**
     * The clock is the service's, handed to the domain and never read there. Fixed here so "the next 08:00"
     * is a fact in the test rather than whatever the box thinks the time is.
     */
    @Mock Clock clock;

    private static final ZoneId OSLO = ZoneId.of("Europe/Oslo");
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 9, 10, 15, 59, 0, 0, OSLO);

    @InjectMocks ChatService service;

    /** The conversation store as a map; a change is one step, as the adapter's is. */
    static class KeptConversations implements ForPersistingConversations {
        private final Map<Operator, Conversation> kept = new HashMap<>();

        @Override
        public Optional<Conversation> load(Operator operator) {
            return Optional.ofNullable(kept.get(operator));
        }

        @Override
        public synchronized Conversation update(Operator operator, UnaryOperator<Conversation> change) {
            Conversation after = change.apply(load(operator).orElseGet(() -> Conversation.empty(operator)));
            kept.put(operator, after);
            return after;
        }

        @Override
        public void forget(Operator operator) {
            kept.remove(operator);
        }

        /** Kept before the test starts, without counting as a change the service made. */
        void seed(Conversation conversation) {
            kept.put(conversation.operator(), conversation);
        }

        Conversation of(Operator operator) {
            return load(operator).orElseThrow();
        }
    }

    private static final List<ToolOffer> TOOLS =
        List.of(new ToolOffer(ChatTool.FLEET, () -> "colina27 connected"));

    @BeforeEach
    void memoryIsEmptyUnlessSaidOtherwise() {
        lenient().when(forPersistingMemory.load()).thenReturn(Memory.empty());
        lenient().when(forPersistingSpend.load()).thenReturn(Spend.empty());
        lenient().when(forPersistingErrands.load()).thenReturn(Errands.empty());
        lenient().when(forPersistingMailedConfirmations.load()).thenReturn(MailedConfirmations.empty());
        lenient().when(clock.instant()).thenReturn(NOW.toInstant());
        lenient().when(clock.getZone()).thenReturn(OSLO);
    }

    private VaierConfig configuredWithAKey() {
        return VaierConfig.builder()
            .domain("example.com")
            .anthropicApiKey("sk-ant-api03-the-key")
            .build();
    }

    @Test
    void isAvailable_isTrueOnceAnAnthropicApiKeyIsStored() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));

        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_isFalseWithoutAKey() {
        when(configPersistence.load()).thenReturn(Optional.of(
            VaierConfig.builder().domain("example.com").build()));

        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_isFalseOnAVaierThatHasNeverBeenConfigured() {
        when(configPersistence.load()).thenReturn(Optional.empty());

        assertThat(service.isAvailable()).isFalse();
    }

    /** The key never appears in the question, the prompt or the answer — it is handed to the port and nowhere else. */
    private static final Operator GEIR = Operator.of("geir@example.com");

    /** The model answers with these pieces, so the conversation has something to remember. */
    private void answering(String... chunks) {
        doAnswer(invocation -> {
            Consumer<String> onText = invocation.getArgument(5);
            for (String chunk : chunks) {
                onText.accept(chunk);
            }
            return new ModelUsage("claude-opus-5", 1000, 100, 0, 0);
        }).when(forConversing).converse(anyString(), anyString(), anyList(), anyString(), anyList(), any());
    }

    /** Each call to the model answers with the next of these, whole. */
    private void answeringInTurn(String... answers) {
        int[] call = {0};
        doAnswer(invocation -> {
            Consumer<String> onText = invocation.getArgument(5);
            onText.accept(answers[call[0]++]);
            return new ModelUsage("claude-opus-5", 1000, 100, 0, 0);
        }).when(forConversing).converse(anyString(), anyString(), anyList(), anyString(), anyList(), any());
    }

    /**
     * A phantom confirmation: the model said a card was up and called no action tool. It is told so once,
     * with the tools still offered, and when that makes no card either Vaier says so in the answer itself.
     */
    @Test
    void ask_whoseAnswerClaimsACardNoToolMade_isCorrectedOnce_andNotedWhenStillNoCard() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        answeringInTurn("The card is up.", "Sorry, there is no card.");
        List<String> received = new ArrayList<>();

        service.ask(GEIR, "back up colina", TOOLS, received::add);

        ConfirmationWatch watch = ConfirmationWatch.overCards(List.of(), List.of(), 0);
        verify(forConversing).converse(eq("sk-ant-api03-the-key"), anyString(),
            eq(List.of(new ConversationTurn(Role.OPERATOR, "back up colina"),
                new ConversationTurn(Role.VAIER, "The card is up."))),
            eq(watch.correction()), eq(TOOLS), any());
        verify(forConversing, times(2)).converse(anyString(), anyString(), anyList(), anyString(), anyList(), any());
        String answered = "The card is up.\n\nSorry, there is no card.\n\n" + watch.closingNote();
        assertThat(String.join("", received)).isEqualTo(answered);
        assertThat(forPersistingConversations.of(GEIR).turns()).extracting(ConversationTurn::text)
            .containsExactly("back up colina", answered);
    }

    /**
     * The claim may be about a card from an earlier answer. While the operator still has one open, it is
     * not a phantom: the live retry made a second, identical download card.
     */
    @Test
    void ask_whoseAnswerRefersToACardStillOpen_isNotRetried() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        long now = NOW.toInstant().toEpochMilli();
        ActionProposal proposal = ActionProposal.propose(ChatAction.RUN_BACKUP, Map.of("machine", "Colina 27"), now);
        Bundle bundle = Bundle.offer(MachineId.of("41a14c07-b2b9-4e6f-bb48-3991a11bb862"), "NAS", List.of("/a"),
            "photos", now);
        for (boolean aBundle : new boolean[] { false, true }) {
            reset(forConversing);
            when(forHoldingActionProposals.heldFor(GEIR)).thenReturn(aBundle ? List.of() : List.of(proposal));
            when(forHoldingBundles.heldFor(GEIR)).thenReturn(aBundle ? List.of(bundle) : List.of());
            answering("The card above is waiting for your click.");
            List<String> received = new ArrayList<>();

            service.ask(GEIR, "and how full is the NAS?", TOOLS, received::add);

            verify(forConversing, times(1).description(aBundle ? "a bundle" : "a proposal"))
                .converse(anyString(), anyString(), anyList(), anyString(), anyList(), any());
            assertThat(String.join("", received)).isEqualTo("The card above is waiting for your click.");
        }
    }

    /** An errand's report may mean a mail it sent on an earlier run, still waiting for the operator's yes. */
    @Test
    void run_whoseReportRefersToAMailStillWaiting_isNotRetried() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Errand errand = due();
        long now = errand.nextDue().toEpochMilli();
        when(forPersistingMailedConfirmations.load()).thenReturn(MailedConfirmations.empty().with(
            MailedConfirmation.mint(ActionProposal.propose(ChatAction.RUN_BACKUP, Map.of("machine", "Colina 27"), now),
                GEIR, now).confirmation(), now));
        answering("I mailed you the backup for a yes yesterday; it is still waiting.");

        service.run(errand, TOOLS);

        verify(forConversing, times(1)).converse(anyString(), anyString(), anyList(), anyString(), anyList(), any());
    }

    @Test
    void ask_handsTheStoredKeyThePromptTheKeptConversationAndTheToolsToThePort() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Conversation kept = Conversation.empty(GEIR).with(new ConversationTurn(Role.OPERATOR, "hello"))
            .with(new ConversationTurn(Role.VAIER, "hi."));
        forPersistingConversations.seed(kept);
        answering("Colina.");
        List<String> received = new ArrayList<>();

        service.ask(GEIR, "which machine is red?", TOOLS, received::add);

        verify(forConversing).converse(eq("sk-ant-api03-the-key"),
            eq(ChatPrompt.forFleet("example.com", NOW, Memory.empty(), Errands.empty(), GEIR).text()),
            eq(kept.forModel()), eq("which machine is red?"), eq(TOOLS), any());
        assertThat(received).containsExactly("Colina.");
    }

    /**
     * A <b>follow-up</b>: after a yes, the model gets the kept thread — ending on Vaier's record of the card and
     * what came back — with the domain's instruction in place of a question. Only the answer is kept, so the
     * thread never shows the operator saying something they did not type.
     */
    @Test
    void followUp_asksWithTheInstructionInPlaceOfAQuestion_andKeepsOnlyTheAnswer() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        ActionProposal card = ActionProposal.propose(ChatAction.RUN_BACKUP, Map.of("machine", "Colina 27"), 0);
        Conversation kept = Conversation.empty(GEIR).with(ConversationTurn.recording(
            card.record(true, new ActionWording("Backing up.", null), null), NOW.toInstant()));
        forPersistingConversations.seed(kept);
        answering("It is running", "; I will say when it lands.");
        List<String> received = new ArrayList<>();

        assertThat(service.followUp(GEIR, TOOLS, received::add)).isTrue();

        verify(forConversing).converse(eq("sk-ant-api03-the-key"),
            eq(ChatPrompt.forFleet("example.com", NOW, Memory.empty(), Errands.empty(), GEIR).text()),
            eq(kept.forModel()), eq(ActionProposal.FOLLOW_UP), eq(TOOLS), any());
        assertThat(received).containsExactly("It is running", "; I will say when it lands.");
        assertThat(forPersistingConversations.of(GEIR))
            .isEqualTo(kept.withAnswer("It is running; I will say when it lands.", NOW.toInstant()));
    }

    /** Nothing owed — "Not now", or already answered — costs nothing: no call to the model, nothing kept. */
    @Test
    void followUp_withNoYesToFollow_asksTheModelNothing() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        forPersistingConversations.seed(Conversation.empty(GEIR)
            .with(new ConversationTurn(Role.VAIER, "Colina is green.")));

        assertThat(service.followUp(GEIR, TOOLS, text -> { })).isFalse();

        verifyNoInteractions(forConversing);
        verify(forPersistingConversations, never()).update(any(), any());
    }

    /** Slice 3: the question and the whole answer are kept, in order, under the operator who asked. */
    @Test
    void ask_remembersTheQuestionAndTheAnswer() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        answering("Colina", " is red.");

        service.ask(GEIR, "which machine is red?", TOOLS, text -> { });

        assertThat(forPersistingConversations.of(GEIR).turns()).containsExactly(
            ConversationTurn.said(Role.OPERATOR, "which machine is red?", NOW.toInstant()),
            ConversationTurn.said(Role.VAIER, "Colina is red.", NOW.toInstant()));
    }

    /** An answer that never came is not a turn; the question alone is kept, so the thread stays honest. */
    @Test
    void ask_keepsTheQuestionEvenWhenTheAnswerWasEmpty() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        answering();

        service.ask(GEIR, "anything?", TOOLS, text -> { });

        assertThat(forPersistingConversations.of(GEIR).turns()).extracting(ConversationTurn::text)
            .containsExactly("anything?");
    }

    /**
     * Once the thread is long, the model is asked — after the answer, with no tools — to summarise the
     * older turns, and what is kept is the summary plus the last turns verbatim. The answer itself was
     * already streamed, so the operator waits for nothing but the busy state.
     */
    @Test
    void ask_compactsALongConversationWithTheModelsOwnSummary() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Conversation longOne = Conversation.empty(GEIR);
        for (int i = 0; i < Conversation.MAX_TURNS; i++) {
            longOne = longOne.with(new ConversationTurn(i % 2 == 0 ? Role.OPERATOR : Role.VAIER, "turn " + i));
        }
        forPersistingConversations.seed(longOne);
        // An errand reports while the summary is being made; its turn outlives the compaction.
        ConversationTurn meanwhile = new ConversationTurn(Role.VAIER, "Errand, Every day at 08:00: all well.");
        doAnswer(invocation -> {
            Consumer<String> onText = invocation.getArgument(5);
            String prompt = invocation.getArgument(1);
            boolean summarising = prompt.equals(ChatPrompt.forCompaction().text());
            if (summarising) {
                forPersistingConversations.update(GEIR, c -> c.with(meanwhile));
            }
            onText.accept(summarising ? "the gist" : "the answer");
            return new ModelUsage("claude-opus-5", 1000, 100, 0, 0);
        }).when(forConversing).converse(anyString(), anyString(), anyList(), anyString(), anyList(), any());

        service.ask(GEIR, "one more?", TOOLS, text -> { });

        Conversation compacted = forPersistingConversations.of(GEIR);
        assertThat(compacted.summary()).isEqualTo("the gist");
        assertThat(compacted.turns()).hasSize(Conversation.KEEP_VERBATIM + 1);
        assertThat(compacted.turns().get(compacted.turns().size() - 2).text()).isEqualTo("the answer");
        assertThat(compacted.turns()).endsWith(meanwhile);
        verify(forConversing).converse(eq("sk-ant-api03-the-key"), eq(ChatPrompt.forCompaction().text()),
            anyList(), anyString(), eq(List.of()), any());
    }

    /** A summary that could not be made is not a lost conversation: the full thread stays, and is said so. */
    @Test
    void ask_keepsTheFullThreadWhenCompactionFails() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Conversation longOne = Conversation.empty(GEIR);
        for (int i = 0; i < Conversation.MAX_TURNS; i++) {
            longOne = longOne.with(new ConversationTurn(i % 2 == 0 ? Role.OPERATOR : Role.VAIER, "turn " + i));
        }
        forPersistingConversations.seed(longOne);
        doAnswer(invocation -> {
            String prompt = invocation.getArgument(1);
            if (prompt.equals(ChatPrompt.forCompaction().text())) {
                throw new IllegalArgumentException("Vaier could not sign in to the Claude API; check the key in Settings.");
            }
            Consumer<String> onText = invocation.getArgument(5);
            onText.accept("the answer");
            return new ModelUsage("claude-opus-5", 1000, 100, 0, 0);
        }).when(forConversing).converse(anyString(), anyString(), anyList(), anyString(), anyList(), any());

        service.ask(GEIR, "one more?", TOOLS, text -> { });

        assertThat(forPersistingConversations.of(GEIR).summary()).isNull();
        assertThat(forPersistingConversations.of(GEIR).turns()).hasSize(Conversation.MAX_TURNS + 2);
    }

    /** The prompt is the domain's, built from the fleet's own base domain. */
    @Test
    void ask_buildsTheSystemPromptForThisFleet() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));

        service.ask(GEIR, "anything?", TOOLS, text -> { });

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(forConversing).converse(anyString(), prompt.capture(), anyList(), anyString(), anyList(),
            any());
        assertThat(prompt.getValue()).contains("example.com");
    }

    /**
     * Asked again after the operator cleared the key, Chat refuses rather than reaching for a null key —
     * availability is re-decided on every question, not remembered from when the pane was opened.
     */
    @Test
    void ask_refusesWhenNoAnthropicApiKeyIsStored() {
        when(configPersistence.load()).thenReturn(Optional.of(
            VaierConfig.builder().domain("example.com").build()));

        assertThatThrownBy(() -> service.ask(GEIR, "which machine is red?", TOOLS, text -> { }))
            .isInstanceOf(ChatUnavailableException.class)
            .hasMessageContaining("Add one in Settings");

        verify(forConversing, never()).converse(any(), any(), any(), any(), any(), any());
        verify(forPersistingConversations, never()).update(any(), any());
    }

    // --- the kept conversation (#360 slice 3) --------------------------------------------------------

    @Test
    void get_isTheKeptConversation_orAnEmptyOne() {
        Conversation kept = Conversation.empty(GEIR).with(new ConversationTurn(Role.OPERATOR, "hello"));
        assertThat(service.get(GEIR)).isEqualTo(Conversation.empty(GEIR));

        forPersistingConversations.seed(kept);
        assertThat(service.get(GEIR)).isEqualTo(kept);
    }

    @Test
    void forget_dropsTheKeptConversation() {
        service.forget(GEIR);

        verify(forPersistingConversations).forget(GEIR);
    }

    /**
     * What became of a card is a turn in Vaier's voice, so the next question knows it: a record in its own
     * shape, and later news (an OS upgrade settling) as words.
     */
    @Test
    void remember_appendsWhatBecameOfACardAsVaiersOwnTurn() {
        Conversation kept = Conversation.empty(GEIR).with(new ConversationTurn(Role.OPERATOR, "back up colina"));
        forPersistingConversations.seed(kept);
        ConfirmationRecord record = ConfirmationRecord.declined(new ActionWording("Back up Colina 27 now.", null));

        service.remember(GEIR, record);
        service.remember(GEIR, "The system updates on Colina 27 are installed.");

        assertThat(forPersistingConversations.of(GEIR)).isEqualTo(kept
            .with(ConversationTurn.recording(record, NOW.toInstant()))
            .with(ConversationTurn.said(Role.VAIER, "The system updates on Colina 27 are installed.", NOW.toInstant())));
    }

    // --- proposing and taking (#360 slice 2) ---------------------------------------------------------

    @Test
    void propose_buildsTheProposalAndHoldsIt() {
        ActionProposal proposal = service.propose(GEIR, ChatAction.RUN_BACKUP, Map.of("machine", "Colina 27"));

        assertThat(proposal.wording()).isEqualTo(ChatAction.RUN_BACKUP.wording(Map.of("machine", "Colina 27")));
        verify(forHoldingActionProposals).hold(GEIR, proposal);
    }

    @Test
    void take_handsBackALiveProposalOnce() {
        ActionProposal proposal = ActionProposal.propose(ChatAction.RUN_BACKUP, Map.of("machine", "Colina 27"),
            System.currentTimeMillis());
        when(forHoldingActionProposals.take("p1")).thenReturn(Optional.of(proposal));

        assertThat(service.take("p1")).isSameAs(proposal);
    }

    @Test
    void take_refusesACardThatIsGone() {
        when(forHoldingActionProposals.take("p1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.take("p1"))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("That card is gone; ask again.");
    }

    @Test
    void take_refusesACardThatHasExpired() {
        ActionProposal stale = ActionProposal.propose(ChatAction.RUN_BACKUP, Map.of("machine", "Colina 27"),
            System.currentTimeMillis() - ActionProposal.TTL.toMillis() - 1);
        when(forHoldingActionProposals.take("p1")).thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> service.take("p1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("That card has expired; ask again.");
    }

    // --- mailed confirmations: an errand's actions, asked by mail -------------------------------------

    /** Kept, then mailed to the operator it runs for, with the approval link on Vaier's own host. */
    @Test
    void mail_keepsTheConfirmation_andMailsItsLinkToTheOperator() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        when(forPersistingMailedConfirmations.load()).thenReturn(MailedConfirmations.empty());
        when(forSendingAdminNotification.sendTo(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        MailedConfirmation mailed = service.mail(GEIR, ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"));

        ArgumentCaptor<MailedConfirmations> saved = ArgumentCaptor.forClass(MailedConfirmations.class);
        verify(forPersistingMailedConfirmations).save(saved.capture());
        assertThat(saved.getValue().held()).containsExactly(mailed);
        assertThat(mailed.operator()).isEqualTo(GEIR);
        assertThat(mailed.mailedAtEpochMs()).isEqualTo(NOW.toInstant().toEpochMilli());
        verify(forSendingAdminNotification).sendTo(eq("geir@example.com"),
            eq("Marvin asks: " + mailed.proposal().wording().headline()),
            contains("https://vaier.example.com/chat/approvals/"), anyString());
    }

    /** A link nobody was sent is never kept, and a refusal is said before any mail goes. */
    @Test
    void mail_thatCannotReachTheOperator_keepsNothing() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        when(forPersistingMailedConfirmations.load()).thenReturn(MailedConfirmations.empty());
        when(forSendingAdminNotification.sendTo(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.mail(GEIR, ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.9")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Mail is not set up, so the operator cannot be asked.");
        verify(forPersistingMailedConfirmations, never()).save(any());
    }

    /** Opening only looks; taking removes it, so the link runs once. */
    @Test
    void open_looksWithoutTaking_andTakeRemovesIt() {
        MailedConfirmation.Minted minted = MailedConfirmation.mint(ActionProposal.propose(ChatAction.LIFT_BLOCK,
            Map.of("address", "203.0.113.9"), 0), GEIR, NOW.toInstant().toEpochMilli());
        when(forPersistingMailedConfirmations.load()).thenReturn(
            MailedConfirmations.empty().with(minted.confirmation(), NOW.toInstant().toEpochMilli()));

        assertThat(service.open(minted.token(), GEIR)).isEqualTo(minted.confirmation());
        verify(forPersistingMailedConfirmations, never()).save(any());

        assertThat(service.take(minted.token(), GEIR)).isEqualTo(minted.confirmation());
        verify(forPersistingMailedConfirmations).save(MailedConfirmations.empty());
    }

    // --- memory (#360) ------------------------------------------------------------------------------

    /** What Vaier remembers rides in the prompt of every question. */
    @Test
    void ask_putsVaiersMemoryInThePrompt() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Memory memory = Memory.empty().remember("Photos live under /volume1/photo.", 1L);
        when(forPersistingMemory.load()).thenReturn(memory);

        service.ask(GEIR, "anything?", TOOLS, text -> { });

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(forConversing).converse(anyString(), prompt.capture(), anyList(), anyString(), anyList(), any());
        assertThat(prompt.getValue()).contains("Photos live under /volume1/photo.");
    }

    @Test
    void remember_addsTheFactAndSaves() {
        when(forPersistingMemory.load()).thenReturn(Memory.empty());

        Memory.Fact fact = service.remember("Photos live under /volume1/photo.");

        assertThat(fact.text()).isEqualTo("Photos live under /volume1/photo.");
        ArgumentCaptor<Memory> saved = ArgumentCaptor.forClass(Memory.class);
        verify(forPersistingMemory).save(saved.capture());
        assertThat(saved.getValue().facts()).containsExactly(fact);
    }

    @Test
    void forget_dropsTheFactAndSaves() {
        Memory memory = Memory.empty().remember("a", 1L);
        when(forPersistingMemory.load()).thenReturn(memory);

        service.forget(memory.facts().get(0).id());

        ArgumentCaptor<Memory> saved = ArgumentCaptor.forClass(Memory.class);
        verify(forPersistingMemory).save(saved.capture());
        assertThat(saved.getValue().facts()).isEmpty();
    }

    @Test
    void getMemory_isWhatIsKept() {
        Memory memory = Memory.empty().remember("a", 1L);
        when(forPersistingMemory.load()).thenReturn(memory);

        assertThat(service.getMemory()).isEqualTo(memory);
    }

    // --- spend (#360) -------------------------------------------------------------------------------

    /** What an answer cost is counted the moment it is known, under this month. */
    @Test
    void ask_recordsWhatTheAnswerUsedAsThisMonthsSpend() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        answering("Colina.");

        service.ask(GEIR, "which machine is red?", TOOLS, text -> { });

        ArgumentCaptor<Spend> saved = ArgumentCaptor.forClass(Spend.class);
        verify(forPersistingSpend).save(saved.capture());
        assertThat(saved.getValue().month(YearMonth.now()).calls()).isEqualTo(1);
        assertThat(saved.getValue().month(YearMonth.now()).usage().inputTokens()).isEqualTo(1000);
    }

    // --- the web read (#360) ------------------------------------------------------------------------

    /** The service hands the port in and nothing else; which addresses are allowed is the domain's call. */
    @Test
    void read_judgesTheAddressInTheDomain_thenAsksThePort() {
        WebPage page = WebPage.fromText("https://www.wireguard.com/", "a routing table");
        when(forReadingWebPages.read(WebAddress.of("https://www.wireguard.com/"))).thenReturn(page);

        assertThat(service.read("  https://www.wireguard.com/  ")).isSameAs(page);
    }

    /** An address aimed at the fleet never reaches the port at all. */
    @Test
    void read_refusesAnAddressThatIsNotOnThePublicInternet_withoutAskingThePort() {
        assertThatThrownBy(() -> service.read("http://169.254.169.254/latest/meta-data/"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("only the public internet");

        verifyNoInteractions(forReadingWebPages);
    }

    @Test
    void search_judgesTheQueryInTheDomain_thenAsksThePort() {
        WebSearchResults found = WebSearchResults.found("borg prune",
            List.of(new WebSearchResult("Borg", "https://borgbackup.org/", "keep-daily")));
        when(forSearchingTheWeb.search("borg prune")).thenReturn(found);

        assertThat(service.search("  borg prune  ")).isSameAs(found);
    }

    @Test
    void search_refusesAQueryTheDomainWouldNotSend_withoutAskingThePort() {
        assertThatThrownBy(() -> service.search("  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Say what to search for.");

        verifyNoInteractions(forSearchingTheWeb);
    }

    @Test
    void thisMonthsSpend_isReadFromWhatIsKept() {
        when(forPersistingSpend.load()).thenReturn(Spend.empty()
            .record(new ModelUsage("claude-opus-5", 1_000_000, 100_000, 0, 0), YearMonth.now()));

        assertThat(service.thisMonth().figure()).isEqualTo("$7.50");
    }

    // --- errands: what Marvin is sent off to do later (#360 slice 2) ---------------------------------

    private static final Rhythm DAILY = Rhythm.parse("daily 08:00");

    private Errands oneErrandOf(Operator operator) {
        return Errands.empty().add(operator, "Tell me only if a machine has updates.", DAILY, NOW);
    }

    @Test
    void add_keepsTheErrandUnderTheOperatorWhoAskedAndHandsItBack() {
        Errand added = service.add(GEIR, "  Tell me only if a machine has updates.  ", "daily 08:00");

        assertThat(added.operator()).isEqualTo(GEIR);
        assertThat(added.instruction()).isEqualTo("Tell me only if a machine has updates.");
        assertThat(added.rhythm()).isEqualTo(DAILY);
        assertThat(added.nextDue()).isEqualTo(DAILY.firstDue(NOW));
        ArgumentCaptor<Errands> saved = ArgumentCaptor.forClass(Errands.class);
        verify(forPersistingErrands).save(saved.capture());
        assertThat(saved.getValue().errands()).containsExactly(added);
    }

    /** The rhythm is the domain's to read; a string it will not read never reaches the file. */
    @Test
    void add_refusesARhythmTheDomainWillNotRead_withoutKeepingAnything() {
        assertThatThrownBy(() -> service.add(GEIR, "anything", "every blue moon"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("once 2026-09-12T08:00");

        verify(forPersistingErrands, never()).save(any());
    }

    @Test
    void cancel_dropsTheOperatorsOwnErrand() {
        Errands kept = oneErrandOf(GEIR);
        when(forPersistingErrands.load()).thenReturn(kept);

        service.cancel(GEIR, kept.errands().get(0).id());

        ArgumentCaptor<Errands> saved = ArgumentCaptor.forClass(Errands.class);
        verify(forPersistingErrands).save(saved.capture());
        assertThat(saved.getValue().errands()).isEmpty();
    }

    @Test
    void cancel_ofSomebodyElsesErrand_isRefusedAndChangesNothing() {
        Errands kept = oneErrandOf(Operator.of("someone@else.com"));
        when(forPersistingErrands.load()).thenReturn(kept);

        assertThatThrownBy(() -> service.cancel(GEIR, kept.errands().get(0).id()))
            .isInstanceOf(NotFoundException.class);

        verify(forPersistingErrands, never()).save(any());
    }

    @Test
    void get_isThisOperatorsOwnErrands() {
        Errands kept = oneErrandOf(GEIR).add(Operator.of("someone@else.com"), "not mine", DAILY, NOW);
        when(forPersistingErrands.load()).thenReturn(kept);

        assertThat(service.getErrands(GEIR)).containsExactly(kept.errands().get(0));
    }

    /** Which errands have come round is the domain's decision, taken against the service's own clock. */
    @Test
    void due_isEveryOperatorsErrandsThatHaveComeRound() {
        Errands kept = oneErrandOf(GEIR);
        when(forPersistingErrands.load()).thenReturn(kept);
        when(clock.instant()).thenReturn(kept.errands().get(0).nextDue());

        assertThat(service.due()).containsExactly(kept.errands().get(0));
        when(clock.instant()).thenReturn(NOW.toInstant());
        assertThat(service.due()).isEmpty();
    }

    // --- running one, with nobody watching ----------------------------------------------------------

    private Errand due() {
        Errands kept = oneErrandOf(GEIR);
        when(forPersistingErrands.load()).thenReturn(kept);
        Errand errand = kept.errands().get(0);
        // The scheduler runs an errand when its time has come, so that moment is the clock the run sees.
        when(clock.instant()).thenReturn(errand.nextDue());
        return errand;
    }

    /**
     * An errand starts fresh: no history, the instruction as the question, the errand's own prompt, and the
     * reads it was handed. Memory is the only thing that carries across, and it is in the prompt.
     */
    @Test
    void run_asksTheModelTheErrandItself_withNoHistory() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Errand errand = due();
        answering("colina27 has 3 updates.");

        service.run(errand, TOOLS);

        verify(forConversing).converse(eq("sk-ant-api03-the-key"),
            eq(ChatPrompt.forErrand("example.com", errand.nextDue().atZone(OSLO), Memory.empty(), errand).text()),
            eq(List.of()), eq(errand.instruction()), eq(TOOLS), any());
    }

    /**
     * An errand's report joins a conversation the operator is still in, and starts a fresh one after three
     * quiet hours — decided when the report lands, so a question asked while Marvin ran is never wiped.
     */
    @Test
    void run_startsAFreshThreadOnlyAfterThreeQuietHours() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Errand errand = due();
        Instant ran = errand.nextDue();
        for (Duration since : new Duration[] { Duration.ofMinutes(5), Conversation.QUIET }) {
            Conversation talking = Conversation.empty(GEIR)
                .withExchange("is colina up?", "It is.", ran.minus(since));
            forPersistingConversations.seed(talking);
            answering("colina27 has 3 updates.");

            service.run(errand, TOOLS);

            assertThat(forPersistingConversations.of(GEIR)).as(since.toString())
                .isEqualTo(talking.withErrandReport(new ErrandReport(errand, "colina27 has 3 updates.")
                    .conversationTurn(ran), ran));
            reset(forConversing);
        }
        verify(forPersistingConversations, never()).forget(any());
    }

    @Test
    void run_mailsTheOperatorWhatMarvinFound_andRemembersItAsATurn() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Errand errand = due();
        answering("colina27 has 3 updates.");

        service.run(errand, TOOLS);

        verify(forSendingAdminNotification).sendTo(eq("geir@example.com"),
            eq("Marvin: Tell me only if a machine has updates."),
            contains("colina27 has 3 updates."), eq("errand " + errand.id()));
        assertThat(forPersistingConversations.of(GEIR).turns()).extracting(ConversationTurn::text)
            .containsExactly("Errand, Every day at 08:00: colina27 has 3 updates.");
        verify(forPublishingEvents).publish("chat", "errand-reported", "");
    }

    /** The errand's phantom is a mail claimed for a yes that no action tool sent. */
    @Test
    void run_whoseReportClaimsAMailNoToolSent_isCorrectedOnce_andTheReportSaysNothingWasMailed() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Errand errand = due();
        answeringInTurn("I mailed you the upgrade for a yes.", "Nothing was mailed.");

        service.run(errand, TOOLS);

        ConfirmationWatch watch = ConfirmationWatch.overMail(MailedConfirmations.empty(), GEIR, 0);
        verify(forConversing).converse(anyString(), anyString(), anyList(), eq(watch.correction()), eq(TOOLS), any());
        verify(forSendingAdminNotification).sendTo(eq("geir@example.com"), anyString(),
            contains("Nothing was mailed.\n\n" + watch.closingNote()), eq("errand " + errand.id()));
    }

    /** Notify only on trouble: the one word means no mail, no turn in the thread, and no nudge to the pane. */
    @Test
    void run_thatFoundNothingToReport_sendsNothingAtAll() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Errand errand = due();
        answering(ErrandReport.NOTHING_TO_REPORT);

        service.run(errand, TOOLS);

        verifyNoInteractions(forSendingAdminNotification, forPublishingEvents);
        verify(forPersistingConversations, never()).update(any(), any());
    }

    /** It still moves on, and the pane says why there was no mail. */
    @Test
    void run_movesTheErrandOnToItsNextTime_sayingHowTheRunWent() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Errand errand = due();
        answering("colina27 has 3 updates.");

        service.run(errand, TOOLS);

        ArgumentCaptor<Errands> saved = ArgumentCaptor.forClass(Errands.class);
        verify(forPersistingErrands).save(saved.capture());
        Errand after = saved.getValue().errands().get(0);
        assertThat(after.lastOutcome()).isEqualTo("reported");
        assertThat(after.lastRunAtEpochMs()).isEqualTo(errand.nextDue().toEpochMilli());
        assertThat(after.nextDue()).isEqualTo(errand.nextDue().plus(Duration.ofDays(1)));
    }

    @Test
    void run_thatFoundNothingToReport_stillMovesOn() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Errand errand = due();
        answering(ErrandReport.NOTHING_TO_REPORT);

        service.run(errand, TOOLS);

        ArgumentCaptor<Errands> saved = ArgumentCaptor.forClass(Errands.class);
        verify(forPersistingErrands).save(saved.capture());
        assertThat(saved.getValue().errands().get(0).lastOutcome()).isEqualTo("nothing to report");
    }

    /**
     * An errand whose answer could not be made moves on all the same. Nobody is watching, so a retry would
     * be a retry storm nobody stops — every minute, against a paid API, until the key is fixed.
     */
    @Test
    void run_thatFailed_movesOnAnyway_andMailsNothing() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Errand errand = due();
        doAnswer(invocation -> { throw new IllegalStateException("the API would not answer"); })
            .when(forConversing).converse(anyString(), anyString(), anyList(), anyString(), anyList(), any());

        service.run(errand, TOOLS);

        ArgumentCaptor<Errands> saved = ArgumentCaptor.forClass(Errands.class);
        verify(forPersistingErrands).save(saved.capture());
        assertThat(saved.getValue().errands().get(0).lastOutcome()).isEqualTo("failed");
        assertThat(saved.getValue().errands().get(0).nextDue()).isAfter(errand.nextDue());
        verifyNoInteractions(forSendingAdminNotification);
    }

    /** Nobody signed in has no address; the report still lands in the thread for when they come back. */
    @Test
    void run_forAnOperatorWithNoEmail_keepsTheTurnAndMailsNothing() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Operator nobody = Operator.of(null);
        Errands kept = oneErrandOf(nobody);
        when(forPersistingErrands.load()).thenReturn(kept);
        answering("colina27 has 3 updates.");

        service.run(kept.errands().get(0), TOOLS);

        verifyNoInteractions(forSendingAdminNotification);
        assertThat(forPersistingConversations.load(nobody)).isPresent();
        verify(forPublishingEvents).publish("chat", "errand-reported", "");
    }

    @Test
    void run_refusesWhenNoAnthropicApiKeyIsStored() {
        when(configPersistence.load()).thenReturn(Optional.of(
            VaierConfig.builder().domain("example.com").build()));

        assertThatThrownBy(() -> service.run(oneErrandOf(GEIR).errands().get(0), TOOLS))
            .isInstanceOf(ChatUnavailableException.class);

        verify(forConversing, never()).converse(any(), any(), any(), any(), any(), any());
    }

    /** This operator's errands ride in the prompt of every question, so Marvin knows what is already watched. */
    @Test
    void ask_putsThisOperatorsErrandsInThePrompt() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        when(forPersistingErrands.load()).thenReturn(oneErrandOf(GEIR));

        service.ask(GEIR, "anything?", TOOLS, text -> { });

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(forConversing).converse(anyString(), prompt.capture(), anyList(), anyString(), anyList(), any());
        assertThat(prompt.getValue()).contains("Your errands for this operator:");
        assertThat(prompt.getValue()).contains("Tell me only if a machine has updates.");
    }

    /**
     * The lost update an errand makes possible: a question takes tens of seconds, and an errand that reports
     * while it is in flight appends a turn of its own. The answer is added to the conversation as it stands
     * when the answer is done, never to the one the question loaded.
     */
    @Test
    void ask_keepsATurnThatLandedWhileTheAnswerWasBeingMade() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        ConversationTurn errandTurn = new ConversationTurn(Role.VAIER, "Errand, Every day at 08:00: all well.");
        doAnswer(invocation -> {
            forPersistingConversations.update(GEIR, c -> c.with(errandTurn));
            Consumer<String> onText = invocation.getArgument(5);
            onText.accept("the answer");
            return new ModelUsage("claude-opus-5", 1000, 100, 0, 0);
        }).when(forConversing).converse(anyString(), anyString(), anyList(), anyString(), anyList(), any());

        service.ask(GEIR, "which machine is red?", TOOLS, text -> { });

        assertThat(forPersistingConversations.of(GEIR).turns()).extracting(ConversationTurn::text).containsExactly(
            "Errand, Every day at 08:00: all well.", "which machine is red?", "the answer");
    }
}
