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
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ReverseProxyAuditState;
import net.vaier.domain.port.ForPersistingReverseProxyAuditState;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed store for {@link ReverseProxyAuditState}: the findings admins were last emailed about, as a
 * list of signatures under the root {@code notified:} key in {@code reverse-proxy-audit.yml}. No secrets, so
 * — like {@link DiskPressureStateFileAdapter} — a plain, tolerant SnakeYAML round-trip.
 *
 * <p>An absent file is the healthy state: nothing outstanding. Tolerant on load, and the tolerance errs the
 * safe way — a file that will not parse reads as nothing outstanding, so the next sweep re-alerts about a
 * broken config rather than going quiet about it.
 */
@Component
@Slf4j
public class ReverseProxyAuditStateFileAdapter implements ForPersistingReverseProxyAuditState {

    private static final String FILE_NAME = "reverse-proxy-audit.yml";
    private static final String ROOT_KEY = "notified";

    private final String filePath;

    public ReverseProxyAuditStateFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public ReverseProxyAuditStateFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized Optional<ReverseProxyAuditState> find() {
        File file = new File(filePath);
        if (!file.exists()) {
            return Optional.empty();
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null || !(data.get(ROOT_KEY) instanceof List<?> list)) {
                return Optional.empty();
            }
            Set<String> signature = new LinkedHashSet<>();
            for (Object entry : list) {
                if (entry != null) {
                    signature.add(entry.toString());
                }
            }
            return signature.isEmpty() ? Optional.empty()
                : Optional.of(new ReverseProxyAuditState(signature));
        } catch (Exception e) {
            log.warn("Failed to load the reverse proxy audit state from {}", filePath, e);
            return Optional.empty();
        }
    }

    @Override
    public synchronized void save(ReverseProxyAuditState state) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(ROOT_KEY, new ArrayList<>(state.notifiedSignature()));

        try (FileWriter writer = new FileWriter(file)) {
            new Yaml(options).dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save the reverse proxy audit state to " + filePath, e);
        }
    }

    @Override
    public synchronized void clear() {
        File file = new File(filePath);
        if (file.exists() && !file.delete()) {
            log.warn("Could not clear the reverse proxy audit state at {}", filePath);
        }
    }
}
