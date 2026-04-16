package net.vaier.adapter.driven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

@Component
@Slf4j
public class VaierConfigFileAdapter implements ForPersistingAppConfiguration {

    private static final String CONFIG_FILE_NAME = "vaier-config.yml";
    private final String configFilePath;

    public VaierConfigFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public VaierConfigFileAdapter(String configDir) {
        this.configFilePath = configDir + "/" + CONFIG_FILE_NAME;
    }

    @Override
    public Optional<VaierConfig> load() {
        File file = new File(configFilePath);
        if (!file.exists()) {
            return Optional.empty();
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(fis);
            if (data == null) {
                return Optional.empty();
            }
            return Optional.of(VaierConfig.builder()
                .domain((String) data.get("domain"))
                .awsKey((String) data.get("awsKey"))
                .awsSecret((String) data.get("awsSecret"))
                .acmeEmail((String) data.get("acmeEmail"))
                .smtpHost((String) data.get("smtpHost"))
                .smtpPort((Integer) data.get("smtpPort"))
                .smtpUsername((String) data.get("smtpUsername"))
                .smtpSender((String) data.get("smtpSender"))
                .build());
        } catch (Exception e) {
            log.warn("Failed to load vaier config from {}", configFilePath, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(VaierConfig config) {
        File file = new File(configFilePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domain", config.getDomain());
        data.put("awsKey", config.getAwsKey());
        data.put("awsSecret", config.getAwsSecret());
        data.put("acmeEmail", config.getAcmeEmail());
        data.put("smtpHost", config.getSmtpHost());
        data.put("smtpPort", config.getSmtpPort());
        data.put("smtpUsername", config.getSmtpUsername());
        data.put("smtpSender", config.getSmtpSender());

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(data, writer);
            log.info("Saved vaier configuration to {}", configFilePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save vaier configuration", e);
        }
    }

    @Override
    public boolean exists() {
        return new File(configFilePath).exists();
    }
}
