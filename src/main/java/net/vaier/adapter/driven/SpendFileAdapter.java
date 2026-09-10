package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ModelUsage;
import net.vaier.domain.MonthSpend;
import net.vaier.domain.Spend;
import net.vaier.domain.port.ForPersistingSpend;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One YAML file, {@code spend.yml}: tokens and calls per month, and never a price. */
@Component
@Slf4j
public class SpendFileAdapter implements ForPersistingSpend {

    private static final String FILE_NAME = "spend.yml";
    private final File file;

    public SpendFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    SpendFileAdapter(String configDir) {
        this.file = new File(configDir, FILE_NAME);
    }

    @Override
    public synchronized Spend load() {
        if (!file.exists()) {
            return Spend.empty();
        }
        try (FileInputStream in = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(in);
            List<MonthSpend> months = new ArrayList<>();
            if (data != null && data.get("months") instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> m) {
                        months.add(new MonthSpend(YearMonth.parse(String.valueOf(m.get("month"))),
                            new ModelUsage(String.valueOf(m.get("model")), number(m, "inputTokens"),
                                number(m, "outputTokens"), number(m, "cacheWriteTokens"), number(m, "cacheReadTokens")),
                            (int) number(m, "calls")));
                    }
                }
            }
            return new Spend(months);
        } catch (Exception e) {
            log.warn("The spend kept at {} could not be read; starting from nothing", file, e);
            return Spend.empty();
        }
    }

    @Override
    public synchronized void save(Spend spend) {
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        List<Map<String, Object>> months = new ArrayList<>();
        for (MonthSpend month : spend.months()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("month", month.month().toString());
            entry.put("model", month.usage().model());
            entry.put("inputTokens", month.usage().inputTokens());
            entry.put("outputTokens", month.usage().outputTokens());
            entry.put("cacheWriteTokens", month.usage().cacheWriteTokens());
            entry.put("cacheReadTokens", month.usage().cacheReadTokens());
            entry.put("calls", month.calls());
            months.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("months", months);
        try (FileWriter writer = new FileWriter(file)) {
            new Yaml(options).dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to keep the spend at " + file, e);
        }
    }

    private static long number(Map<?, ?> m, String key) {
        Object value = m.get(key);
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
