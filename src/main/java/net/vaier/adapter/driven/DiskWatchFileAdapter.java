package net.vaier.adapter.driven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.DiskWatch;
import net.vaier.domain.port.ForPersistingDiskWatches;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed store for {@link DiskWatch}es: one entry per watched-or-muted filesystem in
 * {@code disk-watches.yml}, keyed on machine name and mount point together, under the root {@code watches:}
 * key. No secrets, so — like {@code BackupServerFileAdapter} and {@code LanServerFileAdapter} — a plain,
 * tolerant SnakeYAML round-trip with default file permissions, no {@link SecretCipher}.
 *
 * <p><b>Only the exceptions are stored.</b> A filesystem watched at the global threshold is the default and
 * needs no entry, so this file holds only the filesystems someone has muted or given their own threshold. An
 * absent file is the healthy first-boot state, not an error.
 *
 * <p>Tolerant on load, like the backup adapters: a malformed entry is <em>skipped</em> with a warning rather
 * than aborting the load of every other watch. An entry whose machine or mount point no longer exists — a
 * renamed machine, an unmounted volume — is simply never looked up; it costs nothing and is not an error.
 */
@Component
@Slf4j
public class DiskWatchFileAdapter implements ForPersistingDiskWatches {

    private static final String FILE_NAME = "disk-watches.yml";
    private final String filePath;

    public DiskWatchFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public DiskWatchFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized List<DiskWatch> getAll() {
        File file = new File(filePath);
        if (!file.exists()) return List.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return List.of();
            Object rawWatches = data.get("watches");
            if (!(rawWatches instanceof List<?> list)) return List.of();
            List<DiskWatch> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    DiskWatch watch = deserialize(m);
                    if (watch != null) result.add(watch);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load disk watches from {}", filePath, e);
            return List.of();
        }
    }

    @Override
    public synchronized void save(DiskWatch watch) {
        List<DiskWatch> current = new ArrayList<>(getAll());
        // A watch's identity — machine AND mount point — is DiskWatch.isFor: the domain's, not a rule this
        // adapter re-derives and could drift on.
        current.removeIf(w -> w.isFor(watch.machineName(), watch.mountPoint()));
        current.add(watch);
        writeAll(current);
    }

    /**
     * One YAML entry into a {@link DiskWatch}, or null when it will not make one. The record validates its
     * own machine name, mount point and threshold range, so an entry written by an older Vaier — or by hand
     * — that carries a nonsense threshold is skipped with a warning rather than crashing the load.
     */
    private DiskWatch deserialize(Map<?, ?> m) {
        String machineName = asString(m.get("machineName"));
        String mountPoint = asString(m.get("mountPoint"));
        Integer thresholdPercent = m.get("thresholdPercent") instanceof Number n ? n.intValue() : null;
        Boolean watched = m.get("watched") instanceof Boolean b ? b : null;
        try {
            // DiskWatch.of applies the "absent means watched" policy. It is the domain's — a store that held
            // its own default would be a second place to silently unwatch a disk, which is the #325 bug.
            return DiskWatch.of(machineName, mountPoint, watched, thresholdPercent);
        } catch (IllegalArgumentException e) {
            log.warn("Skipping invalid disk-watch entry in {}: {}", FILE_NAME, e.getMessage());
            return null;
        }
    }

    private void writeAll(List<DiskWatch> watches) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (DiskWatch w : watches) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("machineName", w.machineName());
            entry.put("mountPoint", w.mountPoint());
            entry.put("watched", w.watched());
            if (w.thresholdPercent() != null) {
                entry.put("thresholdPercent", w.thresholdPercent());
            }
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("watches", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save disk watches to " + filePath, e);
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
