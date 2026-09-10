package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.Memory;
import net.vaier.domain.Memory.Fact;
import net.vaier.domain.port.ForPersistingMemory;
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

/** One YAML file, {@code memory.yml}, beside every other file Vaier keeps. */
@Component
@Slf4j
public class MemoryFileAdapter implements ForPersistingMemory {

    private static final String FILE_NAME = "memory.yml";
    private final File file;

    public MemoryFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    MemoryFileAdapter(String configDir) {
        this.file = new File(configDir, FILE_NAME);
    }

    @Override
    public synchronized Memory load() {
        if (!file.exists()) {
            return Memory.empty();
        }
        try (FileInputStream in = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(in);
            List<Fact> facts = new ArrayList<>();
            if (data != null && data.get("facts") instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> m) {
                        facts.add(new Fact(String.valueOf(m.get("id")), String.valueOf(m.get("text")),
                            ((Number) m.get("rememberedAt")).longValue()));
                    }
                }
            }
            return new Memory(facts);
        } catch (Exception e) {
            log.warn("Vaier's memory at {} could not be read; starting with none", file, e);
            return Memory.empty();
        }
    }

    @Override
    public synchronized void save(Memory memory) {
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        List<Map<String, Object>> facts = new ArrayList<>();
        for (Fact fact : memory.facts()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", fact.id());
            entry.put("text", fact.text());
            entry.put("rememberedAt", fact.rememberedAtEpochMs());
            facts.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("facts", facts);
        try (FileWriter writer = new FileWriter(file)) {
            new Yaml(options).dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to keep Vaier's memory at " + file, e);
        }
    }
}
