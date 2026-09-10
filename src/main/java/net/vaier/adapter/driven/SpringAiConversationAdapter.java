package net.vaier.adapter.driven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.AskCapability;
import net.vaier.domain.ConversationTurn;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The one class in Vaier that knows Spring AI exists (#360). It translates and nothing else: a
 * <b>Conversation</b> and a list of <b>Ask tool</b>s go in, the answer comes back in pieces. Which reads
 * exist, how they are worded and whether Ask may be used at all are all decided elsewhere.
 *
 * <p>The model is built per call rather than once at startup, because the <b>Anthropic API key</b> comes
 * from Settings at runtime — an operator who pastes a new key must not have to restart Vaier for it to take.
 */
@Component
@Slf4j
public class SpringAiConversationAdapter implements ForConversing {

    /** What Vaier asks for. Pinned here, in the one place that speaks to the API at all. */
    private static final String MODEL = "claude-opus-5";
    private static final int MAX_TOKENS = 4096;

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
    public void converse(String apiKey, String systemPrompt, List<ConversationTurn> history, String question,
                         List<ToolOffer> tools, Consumer<String> onText) {
        try {
            ChatClient.create(chatModels.apply(apiKey))
                .prompt()
                .system(systemPrompt)
                .messages(history.stream().map(SpringAiConversationAdapter::asMessage).toList())
                .user(question)
                .toolCallbacks(tools.stream().map(SpringAiConversationAdapter::asToolCallback).toList())
                .stream()
                .content()
                .doOnNext(onText)
                .blockLast();
        } catch (Exception e) {
            // The raw text can carry the key verbatim — Anthropic echoes it in a 401 — so it is logged
            // here at the boundary and never returned.
            log.warn("Ask could not hold the conversation: {}", e.toString());
            throw new IllegalArgumentException(COULD_NOT_SIGN_IN);
        }
    }

    /**
     * The request options. The system prompt and the tool list are the two things that never change between
     * turns, so they are the two things worth caching. No thinking budget is set: that builder offers only
     * the old token-budget shape, which Claude Opus 5 refuses — left out, it thinks adaptively by itself.
     */
    static AnthropicChatOptions askOptions() {
        return AnthropicChatOptions.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .cacheOptions(AnthropicCacheOptions.builder()
                .strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS)
                .build())
            .build();
    }

    private static ChatModel anthropicModel(String apiKey) {
        return AnthropicChatModel.builder()
            .anthropicApi(AnthropicApi.builder().apiKey(apiKey).build())
            .defaultOptions(askOptions())
            .build();
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
        AskCapability tool = offer.tool();
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
            arguments.forEach((name, value) -> strings.put(name, value == null ? null : String.valueOf(value)));
        }
        return strings;
    }

    private static String schemaFor(AskCapability tool) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (ToolParameter parameter : tool.parameters()) {
            properties.putObject(parameter.name())
                .put("type", "string")
                .put("description", parameter.description());
        }
        tool.parameters().forEach(parameter -> schema.withArray("required").add(parameter.name()));
        schema.put("additionalProperties", false);
        return schema.toString();
    }
}
