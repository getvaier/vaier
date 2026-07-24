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
                        // Minting is correct here and only here: this is the one-time promotion of a
                        // pre-LanServer legacy file, so these machines have no identity yet to preserve.
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
                    String description = asString(m.get("description"));
                    net.vaier.domain.DeviceCategory deviceCategory =
                        parseDeviceCategory(asString(m.get("deviceCategory")));
                    Boolean sshAccessOverride = m.get("sshAccessOverride") instanceof Boolean b2 ? b2 : null;
                    net.vaier.domain.MachineId machineId = readMachineId(asString(m.get("id")), name);
                    if (name != null && lanAddress != null && machineId != null) {
                        result.add(new LanServer(name, lanAddress, runsDocker, dockerPort, description,
                            deviceCategory, sshAccessOverride, machineId));
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
            // Identity first, so it reads as the key it is when someone opens the file.
            entry.put("id", s.machineId().value());
            entry.put("name", s.name());
            entry.put("lanAddress", s.lanAddress());
            entry.put("runsDocker", s.runsDocker());
            if (s.runsDocker() && s.dockerPort() != null) {
                entry.put("dockerPort", s.dockerPort());
            }
            if (s.description() != null) {
                entry.put("description", s.description());
            }
            if (s.deviceCategory() != null) {
                entry.put("deviceCategory", s.deviceCategory().name());
            }
            // Persist the SSH-access override only when the operator has pinned one; absent = smart default.
            if (s.sshAccessOverride() != null) {
                entry.put("sshAccessOverride", s.sshAccessOverride());
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

    /**
     * The stored {@link net.vaier.domain.MachineId} for an entry, or null when it has none or the value
     * is malformed — the caller then skips the entry.
     *
     * <p>A stored machine's identity is <em>read</em>, never minted. Inventing an id here would produce a
     * machine that looks right in the UI but is a stranger to every record keyed on it — its credential,
     * its host-key pin, its backup jobs. Skipping loudly leaves the operator a file to fix; a silent mint
     * would leave them a fleet to debug.
     */
    private static net.vaier.domain.MachineId readMachineId(String raw, String name) {
        // name comes from lan-servers.yml, which can be hand-edited — collapse any CR/LF so a
        // malformed value can't forge multiline log entries.
        String safeName = name == null ? "(unnamed)" : name.replaceAll("[\r\n]+", "_");
        if (raw == null || raw.isBlank()) {
            log.error("LAN server '{}' in {} has no id — skipping it. Add an `id:` (a UUID) to that entry.",
                safeName, FILE_NAME);
            return null;
        }
        try {
            return net.vaier.domain.MachineId.of(raw);
        } catch (IllegalArgumentException e) {
            log.error("LAN server '{}' in {} has a malformed id — skipping it: {}",
                safeName, FILE_NAME, e.getMessage());
            return null;
        }
    }

    /**
     * The persisted device-category override, or null when absent. An unrecognised value reads as
     * "no override" (logged) rather than failing the load — the category falls back to detection.
     */
    private static net.vaier.domain.DeviceCategory parseDeviceCategory(String value) {
        try {
            return net.vaier.domain.DeviceCategory.fromString(value);
        } catch (IllegalArgumentException e) {
            // value comes from lan-servers.yml, which can be hand-edited — collapse any CR/LF so a
            // malformed value can't forge multiline log entries.
            log.warn("Unknown device category '{}' in {}, treating as no override",
                value.replaceAll("[\r\n]+", "_"), FILE_NAME);
            return null;
        }
    }
}
