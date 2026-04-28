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
import net.vaier.domain.LanDockerHost;
import net.vaier.domain.port.ForPersistingLanDockerHosts;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

@Component
@Slf4j
public class LanDockerHostFileAdapter implements ForPersistingLanDockerHosts {

    private static final String FILE_NAME = "lan-docker-hosts.yml";
    private final String filePath;

    public LanDockerHostFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public LanDockerHostFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized List<LanDockerHost> getAll() {
        File file = new File(filePath);
        if (!file.exists()) return List.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return List.of();
            Object rawHosts = data.get("hosts");
            if (!(rawHosts instanceof List<?> list)) return List.of();
            List<LanDockerHost> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    String name = asString(m.get("name"));
                    String hostIp = asString(m.get("hostIp"));
                    Object port = m.get("port");
                    if (name != null && hostIp != null && port instanceof Number n) {
                        result.add(new LanDockerHost(name, hostIp, n.intValue()));
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load LAN Docker hosts from {}", filePath, e);
            return List.of();
        }
    }

    @Override
    public synchronized void save(LanDockerHost host) {
        List<LanDockerHost> current = new ArrayList<>(getAll());
        current.removeIf(h -> h.name().equals(host.name()));
        current.add(host);
        writeAll(current);
    }

    @Override
    public synchronized void deleteByName(String name) {
        List<LanDockerHost> current = new ArrayList<>(getAll());
        boolean removed = current.removeIf(h -> h.name().equals(name));
        if (removed) writeAll(current);
    }

    private void writeAll(List<LanDockerHost> hosts) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (LanDockerHost h : hosts) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", h.name());
            entry.put("hostIp", h.hostIp());
            entry.put("port", h.port());
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("hosts", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save LAN Docker hosts to " + filePath, e);
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
