package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.FreeReads;
import net.vaier.domain.port.ForPersistingFreeReads;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * {@code free-reads.yml}: each published service's free reads, by its address. No secrets. A file that will
 * not parse reads as none, so Marvin asks for everything until it is fixed.
 *
 * <pre>
 * services:
 *   openhab.example.com: [/rest/items]
 *   example.com/paperless: [/api/documents/]
 * </pre>
 */
@Component
@Slf4j
public class FreeReadFileAdapter implements ForPersistingFreeReads {

    private static final String FILE_NAME = "free-reads.yml";

    private final String filePath;

    public FreeReadFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public FreeReadFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized FreeReads read() {
        File file = new File(filePath);
        if (!file.exists()) {
            return FreeReads.empty();
        }
        try (FileInputStream in = new FileInputStream(file)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map<?, ?> m) || !(m.get("services") instanceof Map<?, ?> services)) {
                return FreeReads.empty();
            }
            Map<String, Set<String>> byService = new TreeMap<>();
            services.forEach((address, paths) -> {
                Set<String> read = new TreeSet<>();
                if (paths instanceof List<?> list) {
                    list.stream().filter(p -> p != null).forEach(p -> read.add(p.toString()));
                }
                byService.put(String.valueOf(address), read);
            });
            return new FreeReads(byService);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to read the free reads from {}; Marvin asks for every read until it is fixed", filePath, e);
            return FreeReads.empty();
        }
    }

    @Override
    public synchronized void update(UnaryOperator<FreeReads> change) {
        FreeReads next = change.apply(read());
        Map<String, Object> services = new LinkedHashMap<>();
        next.byService().forEach((address, paths) -> services.put(address, new ArrayList<>(paths)));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("services", services);
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
            throw new IllegalStateException("Failed to save the free reads to " + filePath, e);
        }
    }
}
