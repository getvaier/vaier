package net.vaier.adapter.driven;

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
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupRunStatus;
import net.vaier.domain.port.ForRecordingBackupRuns;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed recorder for fleet-backup {@link BackupRun}s: the latest run per job in
 * {@code backup-runs.yml}, keyed on {@code jobName}, so a job's last-known status (including an
 * in-flight {@code RUNNING}) survives a Vaier restart. Recording a new run replaces the entry for the
 * same job. Runs hold no secrets — the summary and archive name never carry the passphrase — so every
 * field is stored in the clear. Replaces the ephemeral Slice-1 {@code InMemoryBackupRunAdapter}.
 */
@Component
@Slf4j
public class BackupRunFileAdapter implements ForRecordingBackupRuns {

    private static final String FILE_NAME = "backup-runs.yml";
    private final String filePath;

    public BackupRunFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public BackupRunFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized void record(BackupRun run) {
        List<BackupRun> current = new ArrayList<>(getAll());
        current.removeIf(r -> r.jobName().equals(run.jobName()));
        current.add(run);
        writeAll(current);
    }

    @Override
    public synchronized Optional<BackupRun> latestForJob(String jobName) {
        return getAll().stream().filter(r -> r.jobName().equals(jobName)).findFirst();
    }

    @Override
    public synchronized List<BackupRun> getAll() {
        File file = new File(filePath);
        if (!file.exists()) return List.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return List.of();
            Object rawRuns = data.get("runs");
            if (!(rawRuns instanceof List<?> list)) return List.of();
            List<BackupRun> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    BackupRun run = deserialize(m);
                    if (run != null) result.add(run);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load backup runs from {}", filePath, e);
            return List.of();
        }
    }

    private BackupRun deserialize(Map<?, ?> m) {
        String runId = asString(m.get("runId"));
        String jobName = asString(m.get("jobName"));
        String repositoryName = asString(m.get("repositoryName"));
        String machineName = asString(m.get("machineName"));
        BackupRunStatus status = parseStatus(asString(m.get("status")));
        Instant startedAt = parseInstant(asString(m.get("startedAt")));
        Instant finishedAt = parseInstant(asString(m.get("finishedAt")));
        Integer exitCode = m.get("exitCode") instanceof Number n ? n.intValue() : null;
        String archiveName = asString(m.get("archiveName"));
        String summary = asString(m.get("summary"));
        if (runId == null || jobName == null || status == null || startedAt == null) {
            log.warn("Skipping malformed backup-run entry in {}", FILE_NAME);
            return null;
        }
        return new BackupRun(runId, jobName, repositoryName, machineName, status,
            startedAt, finishedAt, exitCode, archiveName, summary);
    }

    private void writeAll(List<BackupRun> runs) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (BackupRun r : runs) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("runId", r.runId());
            entry.put("jobName", r.jobName());
            if (r.repositoryName() != null) entry.put("repositoryName", r.repositoryName());
            if (r.machineName() != null) entry.put("machineName", r.machineName());
            entry.put("status", r.status().name());
            entry.put("startedAt", r.startedAt().toString());
            if (r.finishedAt() != null) entry.put("finishedAt", r.finishedAt().toString());
            if (r.exitCode() != null) entry.put("exitCode", r.exitCode());
            if (r.archiveName() != null) entry.put("archiveName", r.archiveName());
            if (r.summary() != null) entry.put("summary", r.summary());
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("runs", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save backup runs to " + filePath, e);
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private BackupRunStatus parseStatus(String value) {
        if (value == null) return null;
        try {
            return BackupRunStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown backup-run status '{}' in {}", value.replaceAll("[\r\n]+", "_"), FILE_NAME);
            return null;
        }
    }

    private Instant parseInstant(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            log.warn("Unparseable backup-run instant '{}' in {}", value.replaceAll("[\r\n]+", "_"), FILE_NAME);
            return null;
        }
    }
}
