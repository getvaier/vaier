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
import net.vaier.domain.BackupServer;
import net.vaier.domain.port.ForPersistingBackupServers;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed store for fleet-backup {@link BackupServer} definitions: one entry per server in
 * {@code backup-servers.yml}, keyed on {@code name}, under the root {@code servers:} key. Every field is
 * stored in the clear.
 *
 * <p>Unlike {@link BackupRepositoryFileAdapter}, a Backup server holds <strong>no secrets</strong> — the
 * borg passphrase lives on {@link BackupRepository}, not here — so this adapter uses neither
 * {@link SecretCipher} nor {@link SecureFilePermissions#lockDownFile}. It mirrors the non-secret
 * {@code LanServerFileAdapter} instead: a plain, tolerant SnakeYAML round-trip with default file
 * permissions. (The repository adapter locks its file down specifically because of the encrypted
 * passphrase; there is nothing to protect here.)
 */
@Component
@Slf4j
public class BackupServerFileAdapter implements ForPersistingBackupServers {

    private static final String FILE_NAME = "backup-servers.yml";
    private final String filePath;

    public BackupServerFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public BackupServerFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized List<BackupServer> getAll() {
        File file = new File(filePath);
        if (!file.exists()) return List.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return List.of();
            Object rawServers = data.get("servers");
            if (!(rawServers instanceof List<?> list)) return List.of();
            List<BackupServer> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    BackupServer server = deserialize(m);
                    if (server != null) result.add(server);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load backup servers from {}", filePath, e);
            return List.of();
        }
    }

    @Override
    public synchronized Optional<BackupServer> getByName(String name) {
        return getAll().stream().filter(s -> s.name().equals(name)).findFirst();
    }

    @Override
    public synchronized void save(BackupServer server) {
        List<BackupServer> current = new ArrayList<>(getAll());
        current.removeIf(s -> s.name().equals(server.name()));
        current.add(server);
        writeAll(current);
    }

    @Override
    public synchronized void deleteByName(String name) {
        List<BackupServer> current = new ArrayList<>(getAll());
        boolean removed = current.removeIf(s -> s.name().equals(name));
        if (removed) writeAll(current);
    }

    private BackupServer deserialize(Map<?, ?> m) {
        String name = asString(m.get("name"));
        String machineName = asString(m.get("machineName"));
        String host = asString(m.get("host"));
        Integer sshPort = m.get("sshPort") instanceof Number n ? n.intValue() : null;
        String borgUser = asString(m.get("borgUser"));
        String baseRepoPath = asString(m.get("baseRepoPath"));
        String serverDataPath = asString(m.get("serverDataPath"));
        boolean managed = m.get("managed") instanceof Boolean b && b;
        if (name == null || machineName == null || host == null || sshPort == null) {
            log.warn("Skipping malformed backup-server entry in {}", FILE_NAME);
            return null;
        }
        // borgUser/baseRepoPath default in the record's compact constructor when blank.
        return new BackupServer(name, machineName, host, sshPort, borgUser, baseRepoPath, serverDataPath,
            managed);
    }

    private void writeAll(List<BackupServer> servers) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (BackupServer s : servers) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", s.name());
            entry.put("machineName", s.machineName());
            entry.put("host", s.host());
            entry.put("sshPort", s.sshPort());
            entry.put("borgUser", s.borgUser());
            entry.put("baseRepoPath", s.baseRepoPath());
            if (s.serverDataPath() != null) {
                entry.put("serverDataPath", s.serverDataPath());
            }
            entry.put("managed", s.managed());
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("servers", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save backup servers to " + filePath, e);
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
