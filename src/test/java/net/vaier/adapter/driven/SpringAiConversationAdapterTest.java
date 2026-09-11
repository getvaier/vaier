package net.vaier.adapter.driven;

import net.vaier.domain.ChatTool;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ConversationTurn.Role;
import net.vaier.domain.ToolOffer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import net.vaier.domain.ModelUsage;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The one class in Vaier that knows Spring AI exists (#360). What is worth testing here is the translation:
 * that the prompt, the conversation and the <b>Chat tool</b> catalogue arrive at the model intact, and that
 * the answer comes back in pieces, in order.
 */
class SpringAiConversationAdapterTest {

    private final RecordingChatModel model = new RecordingChatModel();

    private SpringAiConversationAdapter adapter() {
        return new SpringAiConversationAdapter(apiKey -> {
            model.apiKey = apiKey;
            return model;
        });
    }

    private List<String> converse(List<ConversationTurn> history, List<ToolOffer> tools) {
        List<String> received = new ArrayList<>();
        adapter().converse("sk-ant-api03-the-key", "you are Vaier", history, "which machine is red?",
            tools, received::add);
        return received;
    }

    /**
     * What the answer cost, read off the stream the way Spring AI's tool loop actually shapes it: only the
     * first call's chunks carry Anthropic's own usage with the cache split; after a tool result the chunks
     * carry cumulative prompt and completion totals with no split. So the totals come from the last chunk,
     * the cache split from the first call, and each later call is taken to have read the same prefix.
     */
    @Test
    void itReportsTheTokensTheAnswerUsed_fromTheCumulativeTotals_withTheFirstCallsCacheSplit() {
        model.responses = List.of(
            chunk("", own(92, 25, 4651, 0), null),
            chunk("", own(92, 53, 4651, 0), null),
            chunk("Looking", cumulative(1406, 78), null),
            chunk("Colina", cumulative(2757, 84), null),
            chunk(" is red.", cumulative(2757, 219), "end_turn"));
        List<String> received = new ArrayList<>();

        ModelUsage used = adapter().converse("sk-ant-api03-the-key", "you are Vaier", List.of(), "which?",
            List.of(), received::add);

        // three calls: the first wrote 4651 to cache, the two after it each read that prefix back
        assertThat(used).isEqualTo(new ModelUsage("claude-opus-5", 2757, 219, 4651, 9302));
        assertThat(received).containsExactly("Looking", "Colina", " is red.");
    }

    /** One call, no tools: Anthropic's own usage is the whole answer. */
    @Test
    void aSingleCallIsCountedAsItsOwnUsageSays() {
        model.responses = List.of(chunk("half", own(700, 9, 0, 1200), null), chunk("", own(700, 41, 0, 1200), "end_turn"));

        ModelUsage used = adapter().converse("sk-ant-api03-the-key", "you are Vaier", List.of(), "which?",
            List.of(), text -> { });

        assertThat(used).isEqualTo(new ModelUsage("claude-opus-5", 700, 41, 0, 1200));
    }

    private static DefaultUsage own(int in, int out, int cacheWrite, int cacheRead) {
        return new DefaultUsage(in, out, in + out, new AnthropicApi.Usage(in, out, cacheWrite, cacheRead));
    }

    private static DefaultUsage cumulative(int prompt, int completion) {
        return new DefaultUsage(prompt, completion);
    }

    private static ChatResponse chunk(String text, DefaultUsage usage, String finishReason) {
        ChatGenerationMetadata generationMetadata = finishReason == null
            ? ChatGenerationMetadata.NULL
            : ChatGenerationMetadata.builder().finishReason(finishReason).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), generationMetadata)),
            ChatResponseMetadata.builder().usage(usage).build());
    }

    @Test
    void itBuildsTheModelFromTheKeyItWasGiven() {
        converse(List.of(), List.of());

        assertThat(model.apiKey).isEqualTo("sk-ant-api03-the-key");
    }

    @Test
    void itSendsTheSystemPromptAndTheQuestion() {
        converse(List.of(), List.of());

        assertThat(model.prompt.getInstructions())
            .extracting(Message::getMessageType, Message::getText)
            .containsExactly(
                tuple(MessageType.SYSTEM, "you are Vaier"),
                tuple(MessageType.USER, "which machine is red?"));
    }

    /** Order is the whole meaning of a conversation; the question always comes last. */
    @Test
    void itMapsTheConversationInOrderWithTheQuestionLast() {
        converse(List.of(
            new ConversationTurn(Role.OPERATOR, "is colina27 up?"),
            new ConversationTurn(Role.VAIER, "yes, it handshook a minute ago.")), List.of());

        assertThat(model.prompt.getInstructions())
            .extracting(Message::getMessageType, Message::getText)
            .containsExactly(
                tuple(MessageType.SYSTEM, "you are Vaier"),
                tuple(MessageType.USER, "is colina27 up?"),
                tuple(MessageType.ASSISTANT, "yes, it handshook a minute ago."),
                tuple(MessageType.USER, "which machine is red?"));
    }

    /** The names and the wording are the domain's; the adapter only wraps them. */
    @Test
    void itOffersEachToolUnderTheCatalogueNameAndDescription() {
        converse(List.of(), List.of(
            new ToolOffer(ChatTool.FLEET, () -> "colina27 connected"),
            new ToolOffer(ChatTool.DISKS, () -> "colina27 /volume1 86%")));

        List<ToolCallback> callbacks = ((ToolCallingChatOptions) model.prompt.getOptions()).getToolCallbacks();
        assertThat(callbacks).extracting(callback -> callback.getToolDefinition().name())
            .containsExactly("fleet", "disks");
        assertThat(callbacks).extracting(callback -> callback.getToolDefinition().description())
            .containsExactly(ChatTool.FLEET.description(), ChatTool.DISKS.description());
    }

    /** Calling the tool runs the read it was offered with, and nothing else. */
    @Test
    void callingAnOfferedToolPerformsTheReadBehindIt() {
        converse(List.of(), List.of(new ToolOffer(ChatTool.FLEET, () -> "colina27 connected")));

        ToolCallback fleet = ((ToolCallingChatOptions) model.prompt.getOptions()).getToolCallbacks().get(0);
        assertThat(fleet.call("{}")).contains("colina27 connected");
    }

    /**
     * The one tool that takes arguments is offered with a schema naming exactly the domain's parameters, and
     * a call hands those arguments to the read as the model said them. The whole-fleet reads keep their
     * empty schema, so the model is never invited to pass them anything.
     */
    @Test
    void aToolWithParametersIsOfferedWithTheirSchema_andACallHandsThemToTheRead() {
        List<Map<String, String>> seen = new ArrayList<>();
        converse(List.of(), List.of(new ToolOffer(ChatTool.RUN_ON_MACHINE, args -> {
            seen.add(args);
            return "up 3 days";
        })));

        ToolCallback run = ((ToolCallingChatOptions) model.prompt.getOptions()).getToolCallbacks().get(0);
        String schema = run.getToolDefinition().inputSchema();
        assertThat(schema).contains("\"machine\"").contains("\"command\"").contains("\"required\"");
        assertThat(run.call("{\"machine\":\"Colina 27\",\"command\":\"uptime\"}")).contains("up 3 days");
        assertThat(seen).containsExactly(Map.of("machine", "Colina 27", "command", "uptime"));
    }

    /** A parameter that takes many values is offered as an array, and arrives one per line. */
    @Test
    void aParameterThatTakesManyValues_isAnArrayInTheSchema_andArrivesOnePerLine() {
        List<Map<String, String>> seen = new ArrayList<>();
        converse(List.of(), List.of(new ToolOffer(ChatTool.BUNDLE_FILES, args -> {
            seen.add(args);
            return "offered";
        })));

        ToolCallback bundle = ((ToolCallingChatOptions) model.prompt.getOptions()).getToolCallbacks().get(0);
        assertThat(bundle.getToolDefinition().inputSchema()).contains("\"paths\":{\"type\":\"array\"");
        bundle.call("{\"machine\":\"NAS\",\"paths\":[\"/a\",\"/b\"],\"name\":\"x\"}");
        assertThat(seen.get(0)).containsEntry("paths", "/a\n/b").containsEntry("machine", "NAS");
    }

    @Test
    void itForwardsTheAnswerInPiecesInOrder() {
        model.chunks = List.of("Colina", " is ", "red.");

        assertThat(converse(List.of(), List.of())).containsExactly("Colina", " is ", "red.");
    }

    /**
     * The options are pinned here and nowhere else: the model id, the answer budget, and what is cached.
     * The whole conversation is cached, not only the system prompt and tools: an answer makes several
     * calls and every call re-sends everything before it, so the history and the tool results were the
     * bulk of the bill at full price. Cached, each call reads them at a tenth and writes only what is new.
     */
    @Test
    void itPinsTheModelAndCachesTheWholeConversation() {
        AnthropicChatOptions options = SpringAiConversationAdapter.chatOptions();

        assertThat(options.getModel()).isEqualTo("claude-opus-5");
        assertThat(options.getMaxTokens()).isEqualTo(32_768);
        assertThat(options.getCacheOptions().getStrategy().name()).isEqualTo("CONVERSATION_HISTORY");
    }

    /** Adaptive thinking is the model's own; a token budget is the old shape and Claude Opus 5 refuses it. */
    @Test
    void itSetsNoThinkingBudget() {
        assertThat(SpringAiConversationAdapter.chatOptions().getThinking()).isNull();
    }

    /**
     * A rejected key must read as "check the key in Settings", never as a stack trace — and the message must
     * never carry the key itself, which is the one thing an error is most likely to be pasted with.
     */
    @Test
    void aFailedConversationIsRefusedInWordsAndNeverCarriesTheKey() {
        SpringAiConversationAdapter refusing = new SpringAiConversationAdapter(apiKey -> {
            throw new IllegalStateException("401 invalid x-api-key sk-ant-api03-the-key");
        });

        assertThatThrownBy(() -> refusing.converse("sk-ant-api03-the-key", "you are Vaier", List.of(),
            "which machine is red?", List.of(), text -> { }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Vaier could not sign in to the Claude API; check the key in Settings.")
            .hasMessageNotContaining("sk-ant-api03-the-key");
    }

    /** A fake model that records what it was sent and answers with whatever chunks the test set. */
    private static final class RecordingChatModel implements ChatModel {

        private String apiKey;
        private Prompt prompt;
        private List<String> chunks = List.of("Colina is red.");
        private List<ChatResponse> responses;

        @Override
        public ChatResponse call(Prompt request) {
            this.prompt = request;
            return chunk(chunks.get(0));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt request) {
            this.prompt = request;
            return responses != null ? Flux.fromIterable(responses)
                : Flux.fromIterable(chunks).map(RecordingChatModel::chunk);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private static ChatResponse chunk(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }
}
