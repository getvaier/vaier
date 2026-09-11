package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.Errand;
import net.vaier.domain.Errands;
import net.vaier.domain.Operator;
import net.vaier.domain.Rhythm;
import net.vaier.domain.port.ForPersistingErrands;
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

/**
 * One YAML file, {@code errands.yml}, beside every other file Vaier keeps — the same shape and the same
 * posture as {@code memory.yml}: a file that cannot be read is no errands rather than a Vaier that will not
 * start.
 *
 * <p>The <b>rhythm</b> is kept as the string it was written as, so the file can be read by a person, and a
 * single entry whose rhythm this Vaier cannot read is left out rather than taking the others with it.
 */
@Component
@Slf4j
public class ErrandFileAdapter implements ForPersistingErrands {

    private static final String FILE_NAME = "errands.yml";
    private final File file;

    public ErrandFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    ErrandFileAdapter(String configDir) {
        this.file = new File(configDir, FILE_NAME);
    }

    @Override
    public synchronized Errands load() {
        if (!file.exists()) {
            return Errands.empty();
        }
        try (FileInputStream in = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(in);
            List<Errand> errands = new ArrayList<>();
            if (data != null && data.get("errands") instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> kept) {
                        errandOf(kept).ifPresent(errands::add);
                    }
                }
            }
            return new Errands(errands);
        } catch (Exception e) {
            log.warn("Vaier's errands at {} could not be read; starting with none", file, e);
            return Errands.empty();
        }
    }

    private Optional<Errand> errandOf(Map<?, ?> kept) {
        try {
            return Optional.of(Errand.builder()
                .id(String.valueOf(kept.get("id")))
                .operator(Operator.of(String.valueOf(kept.get("operator"))))
                .instruction(String.valueOf(kept.get("instruction")))
                .rhythm(Rhythm.parse(String.valueOf(kept.get("rhythm"))))
                .nextDue(Instant.ofEpochMilli(((Number) kept.get("nextDue")).longValue()))
                .createdAtEpochMs(((Number) kept.get("createdAt")).longValue())
                .lastRunAtEpochMs(kept.get("lastRunAt") instanceof Number ran ? ran.longValue() : null)
                .lastOutcome(kept.get("lastOutcome") == null ? null : String.valueOf(kept.get("lastOutcome")))
                .build());
        } catch (RuntimeException e) {
            log.warn("An errand in {} could not be read and was left out: {}", file, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public synchronized void save(Errands errands) {
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Errand errand : errands.errands()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", errand.id());
            entry.put("operator", errand.operator().key());
            entry.put("instruction", errand.instruction());
            entry.put("rhythm", errand.rhythm().source());
            entry.put("nextDue", errand.nextDue().toEpochMilli());
            entry.put("createdAt", errand.createdAtEpochMs());
            entry.put("lastRunAt", errand.lastRunAtEpochMs());
            entry.put("lastOutcome", errand.lastOutcome());
            kept.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("errands", kept);
        try (FileWriter writer = new FileWriter(file)) {
            new Yaml(options).dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to keep Vaier's errands at " + file, e);
        }
    }
}
