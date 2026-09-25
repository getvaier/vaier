package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ActionProposal;
import net.vaier.domain.ChatAction;
import net.vaier.domain.MailedConfirmation;
import net.vaier.domain.MailedConfirmations;
import net.vaier.domain.Operator;
import net.vaier.domain.port.ForPersistingMailedConfirmations;
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

/**
 * One YAML file, {@code mailed-confirmations.yml}, beside every other file Vaier keeps. It holds each token's
 * digest, never the token. A file that cannot be read is nothing waiting, and one entry that cannot be read
 * is left out rather than taking the others with it.
 */
@Component
@Slf4j
public class MailedConfirmationFileAdapter implements ForPersistingMailedConfirmations {

    private static final String FILE_NAME = "mailed-confirmations.yml";
    private final File file;

    public MailedConfirmationFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    MailedConfirmationFileAdapter(String configDir) {
        this.file = new File(configDir, FILE_NAME);
    }

    @Override
    public synchronized MailedConfirmations load() {
        if (!file.exists()) {
            return MailedConfirmations.empty();
        }
        try (FileInputStream in = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(in);
            List<MailedConfirmation> held = new ArrayList<>();
            if (data != null && data.get("confirmations") instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> kept) {
                        confirmationOf(kept).ifPresent(held::add);
                    }
                }
            }
            return new MailedConfirmations(held);
        } catch (Exception e) {
            log.warn("Vaier's mailed confirmations at {} could not be read; starting with none", file, e);
            return MailedConfirmations.empty();
        }
    }

    private Optional<MailedConfirmation> confirmationOf(Map<?, ?> kept) {
        try {
            Map<String, String> arguments = new LinkedHashMap<>();
            ((Map<?, ?>) kept.get("arguments")).forEach((k, v) -> arguments.put(String.valueOf(k), String.valueOf(v)));
            return Optional.of(MailedConfirmation.builder()
                .tokenDigest(String.valueOf(kept.get("tokenDigest")))
                .operator(Operator.of(String.valueOf(kept.get("operator"))))
                .proposal(new ActionProposal(String.valueOf(kept.get("id")),
                    ChatAction.valueOf(String.valueOf(kept.get("action"))), Map.copyOf(arguments),
                    String.valueOf(kept.get("sentence")), ((Number) kept.get("proposedAt")).longValue()))
                .mailedAtEpochMs(((Number) kept.get("mailedAt")).longValue())
                .build());
        } catch (RuntimeException e) {
            log.warn("A mailed confirmation in {} could not be read and was left out: {}", file, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public synchronized void save(MailedConfirmations confirmations) {
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        List<Map<String, Object>> kept = new ArrayList<>();
        for (MailedConfirmation confirmation : confirmations.held()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("tokenDigest", confirmation.tokenDigest());
            entry.put("operator", confirmation.operator().key());
            entry.put("id", confirmation.proposal().id());
            entry.put("action", confirmation.proposal().action().name());
            entry.put("arguments", new LinkedHashMap<>(confirmation.proposal().arguments()));
            entry.put("sentence", confirmation.proposal().sentence());
            entry.put("proposedAt", confirmation.proposal().proposedAtEpochMs());
            entry.put("mailedAt", confirmation.mailedAtEpochMs());
            kept.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("confirmations", kept);
        try (FileWriter writer = new FileWriter(file)) {
            new Yaml(options).dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to keep Vaier's mailed confirmations at " + file, e);
        }
    }
}
