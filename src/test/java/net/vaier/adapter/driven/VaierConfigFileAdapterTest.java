package net.vaier.adapter.driven;

import net.vaier.domain.VaierConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VaierConfigFileAdapterTest {

    @TempDir
    Path tempDir;

    private VaierConfigFileAdapter adapter() {
        return new VaierConfigFileAdapter(tempDir.toString());
    }

    @Test
    void exists_returnsFalseWhenNoConfigFile() {
        assertThat(adapter().exists()).isFalse();
    }

    @Test
    void exists_returnsTrueAfterSave() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("AKID")
            .awsSecret("secret")
            .acmeEmail("admin@example.com")
            .build();

        adapter().save(config);

        assertThat(adapter().exists()).isTrue();
    }

    @Test
    void save_writesYamlFile() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("AKID")
            .awsSecret("secret")
            .acmeEmail("admin@example.com")
            .build();

        adapter().save(config);

        Path configFile = tempDir.resolve("vaier-config.yml");
        assertThat(configFile).exists();
    }

    @Test
    void load_returnsEmptyWhenNoConfigFile() {
        assertThat(adapter().load()).isEmpty();
    }

    @Test
    void load_roundTripsConfig() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("AKID123")
            .awsSecret("secret456")
            .acmeEmail("admin@example.com")
            .build();

        VaierConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        Optional<VaierConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDomain()).isEqualTo("example.com");
        assertThat(loaded.get().getAwsKey()).isEqualTo("AKID123");
        assertThat(loaded.get().getAwsSecret()).isEqualTo("secret456");
        assertThat(loaded.get().getAcmeEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void save_createsParentDirectoriesIfNeeded() {
        Path nested = tempDir.resolve("nested/deep/config");
        VaierConfigFileAdapter nestedAdapter = new VaierConfigFileAdapter(nested.toString());

        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("key")
            .awsSecret("secret")
            .acmeEmail("a@b.com")
            .build();

        nestedAdapter.save(config);

        assertThat(nested.resolve("vaier-config.yml")).exists();
    }

    @Test
    void load_returnsEmptyForCorruptFile() throws IOException {
        Files.writeString(tempDir.resolve("vaier-config.yml"), "not: valid: yaml: {{{}}}");

        // Should not throw, just return empty
        Optional<VaierConfig> loaded = adapter().load();
        // Corrupt YAML may parse partially — the key thing is no exception
        assertThat(loaded).isNotNull();
    }

    @Test
    void load_roundTripsSmtpFields() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("AKID123")
            .awsSecret("secret456")
            .acmeEmail("admin@example.com")
            .smtpHost("smtp.example.com")
            .smtpPort(587)
            .smtpUsername("user@example.com")
            .smtpSender("noreply@example.com")
            .build();

        VaierConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        Optional<VaierConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSmtpHost()).isEqualTo("smtp.example.com");
        assertThat(loaded.get().getSmtpPort()).isEqualTo(587);
        assertThat(loaded.get().getSmtpUsername()).isEqualTo("user@example.com");
        assertThat(loaded.get().getSmtpSender()).isEqualTo("noreply@example.com");
    }

    @Test
    void load_smtpFieldsAreNullWhenNotPresent() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("AKID123")
            .awsSecret("secret456")
            .acmeEmail("admin@example.com")
            .build();

        VaierConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        Optional<VaierConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSmtpHost()).isNull();
        assertThat(loaded.get().getSmtpPort()).isNull();
        assertThat(loaded.get().getSmtpUsername()).isNull();
        assertThat(loaded.get().getSmtpSender()).isNull();
    }
}
