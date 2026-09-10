package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One YAML file per operator under {@code conversations/}, beside every other file Vaier keeps. */
@Component
@Slf4j
public class ConversationFileAdapter implements ForPersistingConversations {

    private static final String DIR_NAME = "conversations";
    private final File dir;

    public ConversationFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    ConversationFileAdapter(String configDir) {
        this.dir = new File(configDir, DIR_NAME);
    }

    @Override
    public synchronized Optional<Conversation> load(Operator operator) {
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
                        turns.add(new ConversationTurn(Role.valueOf(String.valueOf(m.get("role"))),
                            String.valueOf(m.get("text"))));
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

    @Override
    public synchronized void save(Conversation conversation) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        List<Map<String, Object>> turns = new ArrayList<>();
        for (ConversationTurn turn : conversation.turns()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", turn.role().name());
            entry.put("text", turn.text());
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

    @Override
    public synchronized void forget(Operator operator) {
        File file = fileOf(operator);
        if (file.exists() && !file.delete()) {
            log.warn("The conversation kept at {} could not be removed", file);
        }
    }

    private File fileOf(Operator operator) {
        return new File(dir, operator.fileName() + ".yml");
    }
}
