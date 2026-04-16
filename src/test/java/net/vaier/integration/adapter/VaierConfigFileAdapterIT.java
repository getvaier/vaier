package net.vaier.integration.adapter;

import net.vaier.adapter.driven.VaierConfigFileAdapter;
import net.vaier.domain.VaierConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for VaierConfigFileAdapter against a real temp directory.
 */
class VaierConfigFileAdapterIT {

    @TempDir
    java.nio.file.Path tempDir;

    VaierConfigFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new VaierConfigFileAdapter(tempDir.toString());
    }

    @Test
    void load_returnsEmptyWhenFileDoesNotExist() {
        assertThat(adapter.load()).isEmpty();
    }

    @Test
    void exists_returnsFalseBeforeSave() {
        assertThat(adapter.exists()).isFalse();
    }

    @Test
    void exists_returnsTrueAfterSave() {
        adapter.save(VaierConfig.builder().domain("example.com").build());
        assertThat(adapter.exists()).isTrue();
    }

    @Test
    void saveAndLoad_roundTripsAllFields() {
        VaierConfig config = VaierConfig.builder()
                .domain("example.com")
                .awsKey("AKIAIOSFODNN7EXAMPLE")
                .awsSecret("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
                .acmeEmail("admin@example.com")
                .smtpHost("smtp.example.com")
                .smtpPort(587)
                .smtpUsername("user@example.com")
                .smtpSender("noreply@example.com")
                .build();

        adapter.save(config);
        Optional<VaierConfig> loaded = adapter.load();

        assertThat(loaded).isPresent();
        VaierConfig result = loaded.get();
        assertThat(result.getDomain()).isEqualTo("example.com");
        assertThat(result.getAwsKey()).isEqualTo("AKIAIOSFODNN7EXAMPLE");
        assertThat(result.getAwsSecret()).isEqualTo("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        assertThat(result.getAcmeEmail()).isEqualTo("admin@example.com");
        assertThat(result.getSmtpHost()).isEqualTo("smtp.example.com");
        assertThat(result.getSmtpPort()).isEqualTo(587);
        assertThat(result.getSmtpUsername()).isEqualTo("user@example.com");
        assertThat(result.getSmtpSender()).isEqualTo("noreply@example.com");
    }

    @Test
    void secondSave_overwritesFirstSave() {
        adapter.save(VaierConfig.builder().domain("first.com").awsKey("KEY1").build());
        adapter.save(VaierConfig.builder().domain("second.com").awsKey("KEY2").build());

        Optional<VaierConfig> loaded = adapter.load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDomain()).isEqualTo("second.com");
        assertThat(loaded.get().getAwsKey()).isEqualTo("KEY2");
    }

    @Test
    void saveWithNullFields_loadReturnsNulls() {
        adapter.save(VaierConfig.builder().domain("example.com").build());

        Optional<VaierConfig> loaded = adapter.load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getAwsKey()).isNull();
        assertThat(loaded.get().getSmtpHost()).isNull();
        assertThat(loaded.get().getSmtpPort()).isNull();
    }

    @Test
    void autoCreatesParentDirectories() {
        VaierConfigFileAdapter nestedAdapter = new VaierConfigFileAdapter(
                tempDir.resolve("vaier").resolve("config").toString());

        nestedAdapter.save(VaierConfig.builder().domain("example.com").build());

        assertThat(nestedAdapter.load()).isPresent();
    }
}
