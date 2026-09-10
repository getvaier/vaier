package net.vaier.application.service;

import net.vaier.domain.ActionProposal;
import net.vaier.domain.ChatAction;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.ChatPrompt;
import net.vaier.domain.ChatTool;
import net.vaier.domain.ChatUnavailableException;
import net.vaier.domain.Conversation;
import net.vaier.domain.Operator;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.Memory;
import net.vaier.domain.ModelUsage;
import net.vaier.domain.Spend;
import net.vaier.domain.ConversationTurn.Role;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForConversing;
import net.vaier.domain.port.ForPersistingConversations;
import net.vaier.domain.port.ForPersistingMemory;
import net.vaier.domain.port.ForPersistingSpend;
import net.vaier.domain.port.ForHoldingActionProposals;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock ForPersistingAppConfiguration configPersistence;
    @Mock ForConversing forConversing;
    @Mock ForHoldingActionProposals forHoldingActionProposals;
    @Mock ForPersistingConversations forPersistingConversations;
    @Mock ForPersistingMemory forPersistingMemory;
    @Mock ForPersistingSpend forPersistingSpend;

    @InjectMocks ChatService service;

    private static final List<ToolOffer> TOOLS =
        List.of(new ToolOffer(ChatTool.FLEET, () -> "colina27 connected"));

    @BeforeEach
    void memoryIsEmptyUnlessSaidOtherwise() {
        lenient().when(forPersistingMemory.load()).thenReturn(Memory.empty());
        lenient().when(forPersistingSpend.load()).thenReturn(Spend.empty());
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

    @Test
    void ask_handsTheStoredKeyThePromptTheKeptConversationAndTheToolsToThePort() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        Conversation kept = Conversation.empty(GEIR).with(new ConversationTurn(Role.OPERATOR, "hello"))
            .with(new ConversationTurn(Role.VAIER, "hi."));
        when(forPersistingConversations.load(GEIR)).thenReturn(Optional.of(kept));
        answering("Colina.");
        List<String> received = new ArrayList<>();

        service.ask(GEIR, "which machine is red?", TOOLS, received::add);

        verify(forConversing).converse(eq("sk-ant-api03-the-key"),
            eq(ChatPrompt.forFleet("example.com", LocalDate.now(), Memory.empty()).text()),
            eq(kept.forModel()), eq("which machine is red?"), eq(TOOLS), any());
        assertThat(received).containsExactly("Colina.");
    }

    /** Slice 3: the question and the whole answer are kept, in order, under the operator who asked. */
    @Test
    void ask_remembersTheQuestionAndTheAnswer() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        when(forPersistingConversations.load(GEIR)).thenReturn(Optional.empty());
        answering("Colina", " is red.");

        service.ask(GEIR, "which machine is red?", TOOLS, text -> { });

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(forPersistingConversations).save(saved.capture());
        assertThat(saved.getValue().operator()).isEqualTo(GEIR);
        assertThat(saved.getValue().turns()).containsExactly(
            new ConversationTurn(Role.OPERATOR, "which machine is red?"),
            new ConversationTurn(Role.VAIER, "Colina is red."));
    }

    /** An answer that never came is not a turn; the question alone is kept, so the thread stays honest. */
    @Test
    void ask_keepsTheQuestionEvenWhenTheAnswerWasEmpty() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        when(forPersistingConversations.load(GEIR)).thenReturn(Optional.empty());
        answering();

        service.ask(GEIR, "anything?", TOOLS, text -> { });

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(forPersistingConversations).save(saved.capture());
        assertThat(saved.getValue().turns()).containsExactly(new ConversationTurn(Role.OPERATOR, "anything?"));
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
        when(forPersistingConversations.load(GEIR)).thenReturn(Optional.of(longOne));
        doAnswer(invocation -> {
            Consumer<String> onText = invocation.getArgument(5);
            String prompt = invocation.getArgument(1);
            onText.accept(prompt.equals(ChatPrompt.forCompaction().text()) ? "the gist" : "the answer");
            return new ModelUsage("claude-opus-5", 1000, 100, 0, 0);
        }).when(forConversing).converse(anyString(), anyString(), anyList(), anyString(), anyList(), any());

        service.ask(GEIR, "one more?", TOOLS, text -> { });

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(forPersistingConversations, times(2)).save(saved.capture());
        Conversation compacted = saved.getAllValues().get(1);
        assertThat(compacted.summary()).isEqualTo("the gist");
        assertThat(compacted.turns()).hasSize(Conversation.KEEP_VERBATIM);
        assertThat(compacted.turns().get(compacted.turns().size() - 1))
            .isEqualTo(new ConversationTurn(Role.VAIER, "the answer"));
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
        when(forPersistingConversations.load(GEIR)).thenReturn(Optional.of(longOne));
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

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(forPersistingConversations, times(1)).save(saved.capture());
        assertThat(saved.getValue().summary()).isNull();
        assertThat(saved.getValue().turns()).hasSize(Conversation.MAX_TURNS + 2);
    }

    /** The prompt is the domain's, built from the fleet's own base domain. */
    @Test
    void ask_buildsTheSystemPromptForThisFleet() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        when(forPersistingConversations.load(GEIR)).thenReturn(Optional.empty());

        service.ask(GEIR, "anything?", TOOLS, text -> { });

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(forConversing).converse(anyString(), prompt.capture(), anyList(), anyString(), anyList(),
            any());
        assertThat(prompt.getValue()).contains("example.com");
        assertThat(prompt.getValue()).contains("Answer only from what the tools return.");
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
        verify(forPersistingConversations, never()).save(any());
    }

    // --- the kept conversation (#360 slice 3) --------------------------------------------------------

    @Test
    void get_isTheKeptConversation_orAnEmptyOne() {
        Conversation kept = Conversation.empty(GEIR).with(new ConversationTurn(Role.OPERATOR, "hello"));
        when(forPersistingConversations.load(GEIR)).thenReturn(Optional.of(kept));
        assertThat(service.get(GEIR)).isEqualTo(kept);

        when(forPersistingConversations.load(GEIR)).thenReturn(Optional.empty());
        assertThat(service.get(GEIR)).isEqualTo(Conversation.empty(GEIR));
    }

    @Test
    void forget_dropsTheKeptConversation() {
        service.forget(GEIR);

        verify(forPersistingConversations).forget(GEIR);
    }

    /** What became of a card is a turn in Vaier's voice, so the next question knows it. */
    @Test
    void remember_appendsWhatBecameOfACardAsVaiersOwnTurn() {
        Conversation kept = Conversation.empty(GEIR).with(new ConversationTurn(Role.OPERATOR, "back up colina"));
        when(forPersistingConversations.load(GEIR)).thenReturn(Optional.of(kept));

        service.remember(GEIR, "Proposed: Back up Colina 27 now. (done: Backing up Colina 27 now.)");

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(forPersistingConversations).save(saved.capture());
        assertThat(saved.getValue().turns()).extracting(ConversationTurn::text)
            .containsExactly("back up colina", "Proposed: Back up Colina 27 now. (done: Backing up Colina 27 now.)");
    }

    // --- proposing and taking (#360 slice 2) ---------------------------------------------------------

    @Test
    void propose_buildsTheProposalAndHoldsIt() {
        ActionProposal proposal = service.propose(ChatAction.RUN_BACKUP, Map.of("machine", "Colina 27"));

        assertThat(proposal.sentence()).isEqualTo("Back up Colina 27 now.");
        verify(forHoldingActionProposals).hold(proposal);
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

    // --- memory (#360) ------------------------------------------------------------------------------

    /** What Vaier remembers rides in the prompt of every question. */
    @Test
    void ask_putsVaiersMemoryInThePrompt() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        when(forPersistingConversations.load(GEIR)).thenReturn(Optional.empty());
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
        when(forPersistingConversations.load(GEIR)).thenReturn(Optional.empty());
        answering("Colina.");

        service.ask(GEIR, "which machine is red?", TOOLS, text -> { });

        ArgumentCaptor<Spend> saved = ArgumentCaptor.forClass(Spend.class);
        verify(forPersistingSpend).save(saved.capture());
        assertThat(saved.getValue().month(YearMonth.now()).calls()).isEqualTo(1);
        assertThat(saved.getValue().month(YearMonth.now()).usage().inputTokens()).isEqualTo(1000);
    }

    @Test
    void thisMonthsSpend_isReadFromWhatIsKept() {
        when(forPersistingSpend.load()).thenReturn(Spend.empty()
            .record(new ModelUsage("claude-opus-5", 1_000_000, 100_000, 0, 0), YearMonth.now()));

        assertThat(service.thisMonth().figure()).isEqualTo("$7.50");
    }
}
