package net.vaier.adapter.driven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForPersistingMissingDefaultRoutes;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed latch for which machines admins have been told have <b>no default route</b> (#357): one
 * machine id per entry in {@code missing-default-routes.yml}, under the root {@code missingDefaultRoute:}
 * key. No secrets, so — like {@link DiskPressureStateFileAdapter} — a plain, tolerant SnakeYAML round-trip
 * with default file permissions, no {@link SecretCipher}.
 *
 * <p><b>Why on disk at all.</b> Held in a field it would be wiped by every redeploy — several a day here —
 * and the same email would go out after each one. An absent file is the healthy state, not an error.
 *
 * <p>Tolerant on load, and the tolerance errs the safe way: a file that will not parse means Vaier re-alerts
 * about a machine that is genuinely broken, never goes quiet about one.
 */
@Component
@Slf4j
public class MissingDefaultRouteFileAdapter implements ForPersistingMissingDefaultRoutes {

    private static final String FILE_NAME = "missing-default-routes.yml";
    private static final String ROOT_KEY = "missingDefaultRoute";
    private final String filePath;

    public MissingDefaultRouteFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public MissingDefaultRouteFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized boolean wasAlerted(MachineId machineId) {
        return getAll().contains(machineId.value());
    }

    @Override
    public synchronized void markAlerted(MachineId machineId) {
        Set<String> current = getAll();
        if (current.add(machineId.value())) {
            writeAll(current);
        }
    }

    @Override
    public synchronized void clear(MachineId machineId) {
        Set<String> current = getAll();
        if (current.remove(machineId.value())) {
            writeAll(current);
        }
    }

    /** Every machine currently alerted about. Empty when the fleet is well — the healthy case. */
    private Set<String> getAll() {
        File file = new File(filePath);
        if (!file.exists()) return new LinkedHashSet<>();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return new LinkedHashSet<>();
            if (!(data.get(ROOT_KEY) instanceof List<?> list)) return new LinkedHashSet<>();
            Set<String> result = new LinkedHashSet<>();
            for (Object entry : list) {
                if (entry != null) result.add(entry.toString());
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load missing-default-route state from {}", filePath, e);
            return new LinkedHashSet<>();
        }
    }

    private void writeAll(Set<String> machineIds) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(ROOT_KEY, new ArrayList<>(machineIds));

        try (FileWriter writer = new FileWriter(file)) {
            new Yaml(options).dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save missing-default-route state to " + filePath, e);
        }
    }
}
