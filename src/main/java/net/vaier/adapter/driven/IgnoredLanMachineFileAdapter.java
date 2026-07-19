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
import net.vaier.domain.port.ForManagingIgnoredLanMachines;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed ignore-list for discovered LAN machines (issue #246): a plain list of ignore keys in
 * {@code VAIER_CONFIG_PATH/ignored-lan-machines.yml}. Translation only — the ignore/registration
 * decisions live on {@link net.vaier.domain.DiscoveredLanMachine}. Mirrors {@link LanServerFileAdapter}'s
 * construction idioms so tests can point it at a temp directory.
 */
@Component
@Slf4j
public class IgnoredLanMachineFileAdapter implements ForManagingIgnoredLanMachines {

    private static final String FILE_NAME = "ignored-lan-machines.yml";
    private final String filePath;

    public IgnoredLanMachineFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public IgnoredLanMachineFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized Set<String> getIgnoredKeys() {
        File file = new File(filePath);
        if (!file.exists()) return Set.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return Set.of();
            Object rawKeys = data.get("keys");
            if (!(rawKeys instanceof List<?> list)) return Set.of();
            Set<String> result = new LinkedHashSet<>();
            for (Object entry : list) {
                if (entry != null) result.add(entry.toString());
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load ignored LAN machines from {}", filePath, e);
            return Set.of();
        }
    }

    @Override
    public synchronized void ignore(String ignoreKey) {
        Set<String> current = new LinkedHashSet<>(getIgnoredKeys());
        if (current.add(ignoreKey)) {
            writeAll(current);
        }
    }

    @Override
    public synchronized void unignore(String ignoreKey) {
        Set<String> current = new LinkedHashSet<>(getIgnoredKeys());
        if (current.remove(ignoreKey)) {
            writeAll(current);
        }
    }

    private void writeAll(Set<String> keys) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("keys", new ArrayList<>(keys));

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save ignored LAN machines to " + filePath, e);
        }
    }
}
