package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ActionWording;
import net.vaier.domain.ConfirmationRecord;
import net.vaier.domain.ConfirmationRecord.Outcome;
import net.vaier.domain.Conversation;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ConversationTurn.Role;
import net.vaier.domain.Operator;
import net.vaier.domain.port.ForPersistingConversations;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * One YAML file per operator under {@code conversations/}, beside every other file Vaier keeps. Each operator's
 * file has its own lock, held across a read-change-write and never across anything slower.
 */
@Component
@Slf4j
public class ConversationFileAdapter implements ForPersistingConversations {

    private static final String DIR_NAME = "conversations";
    private final File dir;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public ConversationFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    ConversationFileAdapter(String configDir) {
        this.dir = new File(configDir, DIR_NAME);
    }

    private Object lockOf(Operator operator) {
        return locks.computeIfAbsent(operator.key(), key -> new Object());
    }

    @Override
    public Optional<Conversation> load(Operator operator) {
        synchronized (lockOf(operator)) {
            return read(operator);
        }
    }

    @Override
    public Conversation update(Operator operator, UnaryOperator<Conversation> change) {
        synchronized (lockOf(operator)) {
            Conversation before = read(operator).orElseGet(() -> Conversation.empty(operator));
            Conversation after = change.apply(before);
            if (after != before) {
                write(after);
            }
            return after;
        }
    }

    @Override
    public void forget(Operator operator) {
        synchronized (lockOf(operator)) {
            File file = fileOf(operator);
            if (file.exists() && !file.delete()) {
                log.warn("The conversation kept at {} could not be removed", file);
            }
        }
    }

    private Optional<Conversation> read(Operator operator) {
        File file = fileOf(operator);
        if (!file.exists()) {
            return Optional.empty();
        }
        try (FileInputStream in = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(in);
            if (data == null) {
                return Optional.empty();
            }
            List<ConversationTurn> turns = new ArrayList<>();
            if (data.get("turns") instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> m) {
                        turns.add(turnOf(m));
                    }
                }
            }
            Object summary = data.get("summary");
            return Optional.of(new Conversation(operator, summary == null ? null : String.valueOf(summary), turns));
        } catch (Exception e) {
            log.warn("The conversation kept at {} could not be read; starting afresh", file, e);
            return Optional.empty();
        }
    }

    private static ConversationTurn turnOf(Map<?, ?> m) {
        Instant at = m.get("at") == null ? null : Instant.parse(String.valueOf(m.get("at")));
        if (m.get("card") instanceof Map<?, ?> card) {
            return ConversationTurn.recording(new ConfirmationRecord(
                wording(card.get("headline"), card.get("details")),
                Outcome.valueOf(String.valueOf(card.get("outcome"))),
                card.get("outcomeHeadline") == null ? null
                    : wording(card.get("outcomeHeadline"), card.get("outcomeDetails")),
                card.get("cameBack") == null ? null : String.valueOf(card.get("cameBack"))), at);
        }
        return new ConversationTurn(Role.valueOf(String.valueOf(m.get("role"))), String.valueOf(m.get("text")), at, null);
    }

    private static ActionWording wording(Object headline, Object details) {
        return new ActionWording(String.valueOf(headline), details == null ? null : String.valueOf(details));
    }

    private void write(Conversation conversation) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        List<Map<String, Object>> turns = new ArrayList<>();
        for (ConversationTurn turn : conversation.turns()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", turn.role().name());
            if (turn.at() != null) {
                entry.put("at", turn.at().toString());
            }
            if (turn.confirmation() != null) {
                entry.put("card", cardOf(turn.confirmation()));
            } else {
                entry.put("text", turn.text());
            }
            turns.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("operator", conversation.operator().key());
        if (conversation.summary() != null) {
            root.put("summary", conversation.summary());
        }
        root.put("turns", turns);
        File file = fileOf(conversation.operator());
        try (FileWriter writer = new FileWriter(file)) {
            new Yaml(options).dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to keep the conversation at " + file, e);
        }
    }

    private static Map<String, Object> cardOf(ConfirmationRecord record) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("headline", record.proposed().headline());
        putIfPresent(card, "details", record.proposed().details());
        card.put("outcome", record.outcome().name());
        if (record.result() != null) {
            card.put("outcomeHeadline", record.result().headline());
            putIfPresent(card, "outcomeDetails", record.result().details());
        }
        putIfPresent(card, "cameBack", record.cameBack());
        return card;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private File fileOf(Operator operator) {
        return new File(dir, operator.fileName() + ".yml");
    }
}
