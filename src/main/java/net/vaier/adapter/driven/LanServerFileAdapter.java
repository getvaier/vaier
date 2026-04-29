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
import net.vaier.domain.LanServer;
import net.vaier.domain.port.ForPersistingLanServers;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

@Component
@Slf4j
public class LanServerFileAdapter implements ForPersistingLanServers {

    private static final String FILE_NAME = "lan-servers.yml";
    private static final String LEGACY_FILE_NAME = "lan-docker-hosts.yml";
    private final String filePath;
    private final String legacyFilePath;

    public LanServerFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public LanServerFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
        this.legacyFilePath = configDir + "/" + LEGACY_FILE_NAME;
        migrateLegacyIfNeeded();
    }

    private synchronized void migrateLegacyIfNeeded() {
        File newFile = new File(filePath);
        File legacyFile = new File(legacyFilePath);
        if (newFile.exists() || !legacyFile.exists()) return;

        log.info("Migrating legacy {} to {}", LEGACY_FILE_NAME, FILE_NAME);
        List<LanServer> migrated = readLegacyHosts(legacyFile);
        writeAll(migrated);
        if (!legacyFile.delete()) {
            log.warn("Failed to delete legacy file {} after migration", legacyFilePath);
        }
    }

    private List<LanServer> readLegacyHosts(File legacyFile) {
        try (FileInputStream fis = new FileInputStream(legacyFile)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return List.of();
            Object rawHosts = data.get("hosts");
            if (!(rawHosts instanceof List<?> list)) return List.of();
            List<LanServer> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    String name = asString(m.get("name"));
                    String hostIp = asString(m.get("hostIp"));
                    Object port = m.get("port");
                    if (name != null && hostIp != null && port instanceof Number n) {
                        result.add(new LanServer(name, hostIp, true, n.intValue()));
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to read legacy LAN Docker hosts from {}", legacyFilePath, e);
            return List.of();
        }
    }

    @Override
    public synchronized List<LanServer> getAll() {
        File file = new File(filePath);
        if (!file.exists()) return List.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return List.of();
            Object rawServers = data.get("servers");
            if (!(rawServers instanceof List<?> list)) return List.of();
            List<LanServer> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    String name = asString(m.get("name"));
                    String lanAddress = asString(m.get("lanAddress"));
                    Object runsDockerObj = m.get("runsDocker");
                    boolean runsDocker = runsDockerObj instanceof Boolean b ? b : false;
                    Integer dockerPort = m.get("dockerPort") instanceof Number n ? n.intValue() : null;
                    if (name != null && lanAddress != null) {
                        result.add(new LanServer(name, lanAddress, runsDocker, dockerPort));
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load LAN servers from {}", filePath, e);
            return List.of();
        }
    }

    @Override
    public synchronized void save(LanServer server) {
        List<LanServer> current = new ArrayList<>(getAll());
        current.removeIf(s -> s.name().equals(server.name()));
        current.add(server);
        writeAll(current);
    }

    @Override
    public synchronized void deleteByName(String name) {
        List<LanServer> current = new ArrayList<>(getAll());
        boolean removed = current.removeIf(s -> s.name().equals(name));
        if (removed) writeAll(current);
    }

    private void writeAll(List<LanServer> servers) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (LanServer s : servers) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", s.name());
            entry.put("lanAddress", s.lanAddress());
            entry.put("runsDocker", s.runsDocker());
            if (s.runsDocker() && s.dockerPort() != null) {
                entry.put("dockerPort", s.dockerPort());
            }
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("servers", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save LAN servers to " + filePath, e);
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
