package net.vaier.adapter.driven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.BackupJob;
import net.vaier.domain.port.ForPersistingBackupJobs;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed store for fleet-backup {@link BackupJob} definitions: one entry per job in
 * {@code backup-jobs.yml}, keyed on {@code name}. Jobs hold no secrets, so every field is stored in
 * the clear; the {@code sourcePaths} and {@code excludes} list fields are serialized as YAML lists.
 */
@Component
@Slf4j
public class BackupJobFileAdapter implements ForPersistingBackupJobs {

    private static final String FILE_NAME = "backup-jobs.yml";
    private final String filePath;

    public BackupJobFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public BackupJobFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized List<BackupJob> getAll() {
        File file = new File(filePath);
        if (!file.exists()) return List.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return List.of();
            Object rawJobs = data.get("jobs");
            if (!(rawJobs instanceof List<?> list)) return List.of();
            List<BackupJob> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    BackupJob job = deserialize(m);
                    if (job != null) result.add(job);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load backup jobs from {}", filePath, e);
            return List.of();
        }
    }

    @Override
    public synchronized Optional<BackupJob> getByName(String name) {
        return getAll().stream().filter(j -> j.name().equals(name)).findFirst();
    }

    @Override
    public synchronized List<BackupJob> getByMachine(String machineName) {
        return getAll().stream().filter(j -> j.machineName().equals(machineName)).toList();
    }

    @Override
    public synchronized void save(BackupJob job) {
        List<BackupJob> current = new ArrayList<>(getAll());
        current.removeIf(j -> j.name().equals(job.name()));
        current.add(job);
        writeAll(current);
    }

    @Override
    public synchronized void deleteByName(String name) {
        List<BackupJob> current = new ArrayList<>(getAll());
        boolean removed = current.removeIf(j -> j.name().equals(name));
        if (removed) writeAll(current);
    }

    private BackupJob deserialize(Map<?, ?> m) {
        String name = asString(m.get("name"));
        String machineName = asString(m.get("machineName"));
        String repositoryName = asString(m.get("repositoryName"));
        List<String> sourcePaths = asStringList(m.get("sourcePaths"));
        List<String> excludes = asStringList(m.get("excludes"));
        Integer keepDaily = m.get("keepDaily") instanceof Number n ? n.intValue() : null;
        Integer keepWeekly = m.get("keepWeekly") instanceof Number n ? n.intValue() : null;
        Integer keepMonthly = m.get("keepMonthly") instanceof Number n ? n.intValue() : null;
        String compression = asString(m.get("compression"));
        boolean enabled = m.get("enabled") instanceof Boolean b && b;
        if (name == null || machineName == null || repositoryName == null
            || sourcePaths.isEmpty() || keepDaily == null || keepWeekly == null || keepMonthly == null) {
            log.warn("Skipping malformed backup-job entry in {}", FILE_NAME);
            return null;
        }
        try {
            return new BackupJob(name, machineName, repositoryName, sourcePaths, excludes,
                keepDaily, keepWeekly, keepMonthly, compression, enabled);
        } catch (IllegalArgumentException e) {
            log.warn("Skipping invalid backup-job entry '{}' in {}: {}",
                name.replaceAll("[\r\n]+", "_"), FILE_NAME, e.getMessage());
            return null;
        }
    }

    private void writeAll(List<BackupJob> jobs) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (BackupJob j : jobs) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", j.name());
            entry.put("machineName", j.machineName());
            entry.put("repositoryName", j.repositoryName());
            entry.put("sourcePaths", new ArrayList<>(j.sourcePaths()));
            entry.put("excludes", new ArrayList<>(j.excludes()));
            entry.put("keepDaily", j.keepDaily());
            entry.put("keepWeekly", j.keepWeekly());
            entry.put("keepMonthly", j.keepMonthly());
            entry.put("compression", j.compression());
            entry.put("enabled", j.enabled());
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("jobs", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save backup jobs to " + filePath, e);
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) result.add(item.toString());
        }
        return result;
    }
}
