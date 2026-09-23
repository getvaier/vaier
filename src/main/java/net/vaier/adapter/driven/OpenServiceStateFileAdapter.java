package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.OpenServiceState;
import net.vaier.domain.port.ForPersistingOpenServiceState;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code open-services.yml}: the route names admins were mailed about ({@code notified:}) and the ones meant
 * to be public ({@code meantToBePublic:}). No secrets. A file that will not parse reads as nothing outstanding.
 */
@Component
@Slf4j
public class OpenServiceStateFileAdapter implements ForPersistingOpenServiceState {

    private static final String FILE_NAME = "open-services.yml";
    private static final String NOTIFIED = "notified";
    private static final String MEANT_TO_BE_PUBLIC = "meantToBePublic";

    private final String filePath;

    public OpenServiceStateFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public OpenServiceStateFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized OpenServiceState read() {
        File file = new File(filePath);
        if (!file.exists()) {
            return OpenServiceState.empty();
        }
        try (FileInputStream in = new FileInputStream(file)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> root)) {
                return OpenServiceState.empty();
            }
            return new OpenServiceState(names(root.get(NOTIFIED)), names(root.get(MEANT_TO_BE_PUBLIC)));
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to read the open-service state from {}", filePath, e);
            return OpenServiceState.empty();
        }
    }

    @Override
    public synchronized void save(OpenServiceState state) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(NOTIFIED, new ArrayList<>(new TreeSet<>(state.notified())));
        root.put(MEANT_TO_BE_PUBLIC, new ArrayList<>(new TreeSet<>(state.meantToBePublic())));
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileWriter writer = new FileWriter(file)) {
            new Yaml(options).dump(root, writer);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save the open-service state to " + filePath, e);
        }
    }

    private static Set<String> names(Object raw) {
        Set<String> names = new TreeSet<>();
        if (raw instanceof List<?> list) {
            list.stream().filter(o -> o != null).forEach(o -> names.add(o.toString()));
        }
        return names;
    }
}
