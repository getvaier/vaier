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
 * encrypted at rest via {@link SecretCipher}; every other field (host, port, borg user, repo path,
 * append-only flag) is stored in the clear. The file is locked down to owner-only on every write.
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
        String nasHost = asString(m.get("nasHost"));
        Integer sshPort = m.get("sshPort") instanceof Number n ? n.intValue() : null;
        String borgUser = asString(m.get("borgUser"));
        String repoPath = asString(m.get("repoPath"));
        String passphrase = cipher.decrypt(asString(m.get("passphrase")));
        boolean appendOnly = m.get("appendOnly") instanceof Boolean b && b;
        if (name == null || nasHost == null || repoPath == null || sshPort == null) {
            log.warn("Skipping malformed backup-repository entry in {}", FILE_NAME);
            return null;
        }
        String user = (borgUser == null || borgUser.isBlank()) ? BackupRepository.DEFAULT_BORG_USER : borgUser;
        return new BackupRepository(name, nasHost, sshPort, user, repoPath, passphrase, appendOnly);
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
            entry.put("nasHost", r.nasHost());
            entry.put("sshPort", r.sshPort());
            entry.put("borgUser", r.borgUser());
            entry.put("repoPath", r.repoPath());
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
