package net.vaier.adapter.driven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForTrackingHostKeys;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed trust-on-first-use store of SSH host-key fingerprints, persisted to
 * {@code ssh-known-hosts.yml} as a {@code machineId: fingerprint} map. Plain text — a fingerprint is
 * public key material, not a secret.
 */
@Component
@Slf4j
public class HostKeyFileAdapter implements ForTrackingHostKeys {

    private static final String FILE_NAME = "ssh-known-hosts.yml";
    private final String filePath;

    public HostKeyFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public HostKeyFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized Optional<String> getFingerprint(net.vaier.domain.MachineId machineId) {
        Object value = readAll().get(machineId.value());
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }

    @Override
    public synchronized void pin(net.vaier.domain.MachineId machineId, String fingerprint) {
        Map<String, Object> all = new TreeMap<>(readAll());
        all.put(machineId.value(), fingerprint);
        writeAll(all);
    }

    @Override
    public synchronized void clear(net.vaier.domain.MachineId machineId) {
        Map<String, Object> all = new TreeMap<>(readAll());
        if (all.remove(machineId.value()) != null) {
            writeAll(all);
        }
    }

    private Map<String, Object> readAll() {
        File file = new File(filePath);
        if (!file.exists()) return Map.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return Map.of();
            Object hosts = data.get("hostKeys");
            if (hosts instanceof Map<?, ?> m) {
                Map<String, Object> result = new LinkedHashMap<>();
                m.forEach((k, v) -> result.put(String.valueOf(k), v));
                return result;
            }
            return Map.of();
        } catch (Exception e) {
            log.warn("Failed to load host keys from {}", filePath, e);
            return Map.of();
        }
    }

    private void writeAll(Map<String, Object> hostKeys) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("hostKeys", hostKeys);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save host keys to " + filePath, e);
        }
    }
}
