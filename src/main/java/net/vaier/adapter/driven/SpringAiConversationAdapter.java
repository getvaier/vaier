package net.vaier.adapter.driven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ChatCapability;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ModelUsage;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.ToolParameter;
import net.vaier.domain.port.ForConversing;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The one class in Vaier that knows Spring AI exists (#360). It translates and nothing else: a
 * <b>Conversation</b> and a list of <b>Chat tool</b>s go in, the answer comes back in pieces. Which reads
 * exist, how they are worded and whether Chat may be used at all are all decided elsewhere.
 *
 * <p>The model is built per call rather than once at startup, because the <b>Anthropic API key</b> comes
 * from Settings at runtime — an operator who pastes a new key must not have to restart Vaier for it to take.
 */
@Component
@Slf4j
public class SpringAiConversationAdapter implements ForConversing {

    /** What Vaier asks for. Pinned here, in the one place that speaks to the API at all. */
    private static final String MODEL = "claude-opus-5";
    /**
     * Room for the answer AND for a tool call: bundling a hundred photos is a few thousand tokens of paths
     * before a word is said, and a call cut off mid-JSON at the cap ends the whole answer with nothing.
     */
    private static final int MAX_TOKENS = 32_768;

    /** Said to the operator when the conversation could not be held; it never carries the key. */
    private static final String COULD_NOT_SIGN_IN =
        "Vaier could not sign in to the Claude API; check the key in Settings.";

    /** Only ever renders a tool's input schema; no configuration of Vaier's own is wanted here. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Function<String, ChatModel> chatModels;

    public SpringAiConversationAdapter() {
        this(SpringAiConversationAdapter::anthropicModel);
    }

    /** Takes the model factory so a test can hand in one that answers without an API behind it. */
    SpringAiConversationAdapter(Function<String, ChatModel> chatModels) {
        this.chatModels = chatModels;
    }

    @Override
    public ModelUsage converse(String apiKey, String systemPrompt, List<ConversationTurn> history, String question,
                               List<ToolOffer> tools, Consumer<String> onText) {
        UsageTally tally = new UsageTally();
        try {
            ChatClient.create(chatModels.apply(apiKey))
                .prompt()
                .system(systemPrompt)
                .messages(history.stream().map(SpringAiConversationAdapter::asMessage).toList())
                .user(question)
                .toolCallbacks(tools.stream().map(SpringAiConversationAdapter::asToolCallback).toList())
                .stream()
                .chatResponse()
                .doOnNext(response -> {
                    String text = textOf(response);
                    if (!text.isEmpty()) {
                        onText.accept(text);
                    }
                    tally.see(response);
                })
                .blockLast();
            return tally.total();
        } catch (Exception e) {
            // The raw text can carry the key verbatim — Anthropic echoes it in a 401 — so it is logged
            // here at the boundary and never returned.
            log.warn("Chat could not hold the conversation: {}", e.toString());
            throw new IllegalArgumentException(COULD_NOT_SIGN_IN);
        }
    }

    /**
     * The request options. The whole conversation is cached, not only the system prompt and tools: an
     * answer makes several calls and every call re-sends everything before it, so the history and the tool
     * results were the bulk of the bill at full price. Cached, each call reads them at a tenth of the price
     * and writes only what is new. No thinking budget is set: that builder offers only
     * the old token-budget shape, which Claude Opus 5 refuses — left out, it thinks adaptively by itself.
     */
    static AnthropicChatOptions chatOptions() {
        return AnthropicChatOptions.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .cacheOptions(AnthropicCacheOptions.builder()
                .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
                .build())
            .build();
    }

    private static ChatModel anthropicModel(String apiKey) {
        return AnthropicChatModel.builder()
            .anthropicApi(AnthropicApi.builder().apiKey(apiKey).build())
            .defaultOptions(chatOptions())
            .build();
    }

    private static String textOf(ChatResponse response) {
        Generation result = response.getResult();
        String text = result == null || result.getOutput() == null ? null : result.getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * What an answer cost. Spring AI's tool loop shapes the stream like this, and this is the whole reason
     * the tally is not a sum: only the first call's chunks carry Anthropic's own usage with its cache split;
     * every chunk after a tool result carries prompt and completion totals cumulative over the calls so far,
     * with no cache split and no finish reason until the very end. So the totals are the last chunk's, the
     * cache split is the first call's, and every later call — one per distinct cumulative prompt total — is
     * taken to have read the same cached prefix the first call wrote or read. Vaier's own count, and it
     * says so; the invoice wins if they differ.
     */
    private static final class UsageTally {
        private long prompt;
        private long completion;
        private ModelUsage firstCall;
        private final Set<Long> promptTotalsSeen = new LinkedHashSet<>();

        void see(ChatResponse response) {
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            if (usage == null) {
                return;
            }
            if (usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
                prompt = usage.getPromptTokens();
                promptTotalsSeen.add(prompt);
            }
            if (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0) {
                completion = usage.getCompletionTokens();
            }
            if (usage.getNativeUsage() instanceof AnthropicApi.Usage own && promptTotalsSeen.size() <= 1) {
                firstCall = new ModelUsage(MODEL, orZero(own.inputTokens()), orZero(own.outputTokens()),
                    orZero(own.cacheCreationInputTokens()), orZero(own.cacheReadInputTokens()));
            }
        }

        ModelUsage total() {
            long cacheWrite = firstCall == null ? 0 : firstCall.cacheWriteTokens();
            long cacheRead = firstCall == null ? 0 : firstCall.cacheReadTokens();
            int laterCalls = Math.max(0, promptTotalsSeen.size() - 1);
            ModelUsage total = new ModelUsage(MODEL, prompt, completion, cacheWrite,
                cacheRead + laterCalls * (cacheWrite + cacheRead));
            log.debug("Chat usage: {} over {} call(s)", total, promptTotalsSeen.size());
            return total;
        }

        private static long orZero(Integer value) {
            return value == null ? 0 : value;
        }
    }

    private static Message asMessage(ConversationTurn turn) {
        return turn.role() == ConversationTurn.Role.OPERATOR
            ? new UserMessage(turn.text())
            : new AssistantMessage(turn.text());
    }

    /**
     * A whole-fleet read is offered as a supplier, so the model is never invited to pass it anything. The
     * one tool with parameters is offered with a schema naming exactly the domain's parameters, and a call
     * hands them to the read by name, as strings — the domain reads nothing else.
     */
    private static ToolCallback asToolCallback(ToolOffer offer) {
        ChatCapability tool = offer.tool();
        if (tool.parameters().isEmpty()) {
            Supplier<String> read = () -> offer.read().apply(Map.of());
            return FunctionToolCallback.builder(tool.toolName(), read)
                .description(tool.description())
                .build();
        }
        Function<Map<String, Object>, String> read = arguments -> offer.read().apply(strings(arguments));
        return FunctionToolCallback.builder(tool.toolName(), read)
            .description(tool.description())
            .inputType(Map.class)
            .inputSchema(schemaFor(tool))
            .build();
    }

    private static Map<String, String> strings(Map<String, Object> arguments) {
        Map<String, String> strings = new LinkedHashMap<>();
        if (arguments != null) {
            arguments.forEach((name, value) -> strings.put(name, value == null ? null
                : value instanceof Collection<?> many
                    ? many.stream().map(String::valueOf).collect(Collectors.joining("\n"))
                    : String.valueOf(value)));
        }
        return strings;
    }

    private static String schemaFor(ChatCapability tool) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (ToolParameter parameter : tool.parameters()) {
            ObjectNode property = properties.putObject(parameter.name())
                .put("type", parameter.many() ? "array" : "string")
                .put("description", parameter.description());
            if (parameter.many()) {
                property.putObject("items").put("type", "string");
            }
        }
        tool.parameters().forEach(parameter -> schema.withArray("required").add(parameter.name()));
        schema.put("additionalProperties", false);
        return schema.toString();
    }
}
