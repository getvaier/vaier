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
import net.vaier.domain.BackupRepository;
import net.vaier.domain.port.ForPersistingBackupRepositories;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed store for fleet-backup {@link BackupRepository} definitions: one entry per repository in
 * {@code backup-repositories.yml}, keyed on {@code name}. The {@code passphrase} is a secret and is
 * encrypted at rest via {@link SecretCipher}; every other field (server name, repo-path override,
 * append-only flag) is stored in the clear. The file is locked down to owner-only on every write. An entry
 * missing its {@code serverName} is malformed under the slimmed shape and is skipped with a warning, so a
 * stale old-shape file never crashes the load.
 */
@Component
@Slf4j
public class BackupRepositoryFileAdapter implements ForPersistingBackupRepositories {

    private static final String FILE_NAME = "backup-repositories.yml";
    private final String filePath;
    private final SecretCipher cipher;

    public BackupRepositoryFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"), new SecretCipher());
    }

    public BackupRepositoryFileAdapter(String configDir, SecretCipher cipher) {
        this.filePath = configDir + "/" + FILE_NAME;
        this.cipher = cipher;
    }

    @Override
    public synchronized List<BackupRepository> getAll() {
        File file = new File(filePath);
        if (!file.exists()) return List.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return List.of();
            Object rawRepos = data.get("repositories");
            if (!(rawRepos instanceof List<?> list)) return List.of();
            List<BackupRepository> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    BackupRepository repo = deserialize(m);
                    if (repo != null) result.add(repo);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load backup repositories from {}", filePath, e);
            return List.of();
        }
    }

    @Override
    public synchronized Optional<BackupRepository> getByName(String name) {
        return getAll().stream().filter(r -> r.name().equals(name)).findFirst();
    }

    @Override
    public synchronized void save(BackupRepository repository) {
        List<BackupRepository> current = new ArrayList<>(getAll());
        current.removeIf(r -> r.name().equals(repository.name()));
        current.add(repository);
        writeAll(current);
    }

    @Override
    public synchronized void deleteByName(String name) {
        List<BackupRepository> current = new ArrayList<>(getAll());
        boolean removed = current.removeIf(r -> r.name().equals(name));
        if (removed) writeAll(current);
    }

    private BackupRepository deserialize(Map<?, ?> m) {
        String name = asString(m.get("name"));
        String serverName = asString(m.get("serverName"));
        String repoPath = asString(m.get("repoPath"));
        String passphrase = cipher.decrypt(asString(m.get("passphrase")));
        boolean appendOnly = m.get("appendOnly") instanceof Boolean b && b;
        // repoPath is a nullable override; only name and serverName are required. A missing serverName is a
        // stale old-shape entry -> skip it.
        if (name == null || serverName == null) {
            log.warn("Skipping malformed backup-repository entry in {}", FILE_NAME);
            return null;
        }
        // The name/path fields are now identifier/safe-path validated at construction. A pre-fix entry may
        // carry an unsafe name (e.g. a space, created before names were slugged). Repair it to its safe slug
        // rather than dropping it: a silently dropped repository is invisible to the backup service's
        // get-or-create, which then mints a DUPLICATE repository with a fresh passphrase over the live one
        // and orphans it (borg can no longer decrypt the repo). Only a name that cannot be repaired at all
        // (slugs to nothing) is skipped, with a warning, so one bad entry never aborts the whole load.
        try {
            return new BackupRepository(name, serverName, repoPath, passphrase, appendOnly);
        } catch (IllegalArgumentException e) {
            return repairName(name, serverName, repoPath, passphrase, appendOnly, e);
        }
    }

    /**
     * Repair an entry whose stored {@code name} is not a safe identifier by re-keying it on its slug (e.g.
     * {@code "NUC 02"} → {@code "NUC-02"}), so a legacy repository stays visible and reusable instead of
     * being dropped. A name that slugs to nothing, or is still invalid once slugged, is skipped with a
     * warning. The slug rule is deliberately case-preserving (see {@link BackupRepository#sanitizedName}):
     * lower-casing here would re-key a currently-valid repository and orphan it.
     */
    private BackupRepository repairName(String name, String serverName, String repoPath, String passphrase,
                                        boolean appendOnly, IllegalArgumentException original) {
        try {
            String slug = BackupRepository.sanitizedName(name);
            BackupRepository repaired = new BackupRepository(slug, serverName, repoPath, passphrase, appendOnly);
            log.warn("Repaired unsafe backup-repository name '{}' to '{}' in {}", name, slug, FILE_NAME);
            return repaired;
        } catch (IllegalArgumentException stillInvalid) {
            log.warn("Skipping invalid backup-repository entry in {}: {}", FILE_NAME, original.getMessage());
            return null;
        }
    }

    private void writeAll(List<BackupRepository> repositories) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (BackupRepository r : repositories) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", r.name());
            entry.put("serverName", r.serverName());
            if (r.repoPath() != null) {
                entry.put("repoPath", r.repoPath());
            }
            if (r.passphrase() != null) {
                entry.put("passphrase", cipher.encrypt(r.passphrase()));
            }
            entry.put("appendOnly", r.appendOnly());
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("repositories", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save backup repositories to " + filePath, e);
        }
        SecureFilePermissions.lockDownFile(file.toPath());
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
