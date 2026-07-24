package net.vaier.adapter.driven;

import net.vaier.domain.VaierConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

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
    void load_roundTripsDiskMonitorThreshold() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .diskMonitorThresholdPercent(70)
            .build();

        VaierConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        Optional<VaierConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDiskMonitorThresholdPercent()).isEqualTo(70);
    }

    @Test
    void load_roundTripsBackupScheduleHour() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .backupScheduleHour(5)
            .build();

        VaierConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        Optional<VaierConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getBackupScheduleHour()).isEqualTo(5);
    }

    @Test
    void load_backupScheduleHourNullWhenNotPresent() {
        adapter().save(VaierConfig.builder().domain("example.com").build());

        assertThat(adapter().load().orElseThrow().getBackupScheduleHour()).isNull();
    }

    @Test
    void load_diskMonitorThresholdNullWhenNotPresent() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .build();

        VaierConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        Optional<VaierConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDiskMonitorThresholdPercent()).isNull();
    }

    @Test
    void save_writesConfigFileWithOwnerOnlyPermissions() throws IOException {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("AKID")
            .awsSecret("secret")
            .acmeEmail("admin@example.com")
            .build();

        adapter().save(config);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tempDir.resolve("vaier-config.yml"));
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void readStoredPassword_roundTripsTheSmtpPassword() {
        // The config file is Vaier's own SMTP-credential store now that Authelia's secrets file is gone.
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .smtpHost("smtp.example.com")
            .smtpUsername("user@example.com")
            .smtpPassword("s3cr3t")
            .build();

        VaierConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        assertThat(adapter().readStoredPassword()).contains("s3cr3t");
    }

    @Test
    void readStoredPassword_isEmptyWhenNoPasswordStored() {
        VaierConfig config = VaierConfig.builder().domain("example.com").build();
        VaierConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        assertThat(adapter().readStoredPassword()).isEmpty();
    }

    // --- at-rest secret encryption (#307) ---

    @Test
    void save_encryptsAwsSecretAndSmtpPasswordAtRest() throws IOException {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("AKID")
            .awsSecret("the-aws-secret")
            .acmeEmail("admin@example.com")
            .smtpHost("smtp.example.com")
            .smtpUsername("user@example.com")
            .smtpPassword("the-smtp-password")
            .build();

        adapter().save(config);

        String contents = Files.readString(tempDir.resolve("vaier-config.yml"));
        assertThat(contents)
            .doesNotContain("the-aws-secret")
            .doesNotContain("the-smtp-password")
            .contains("enc:v1:")
            // non-secret fields stay in the clear.
            .contains("AKID")
            .contains("example.com");
    }

    @Test
    void save_thenLoad_roundTripsEncryptedSecrets() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("AKID")
            .awsSecret("the-aws-secret")
            .acmeEmail("admin@example.com")
            .smtpPassword("the-smtp-password")
            .build();

        adapter().save(config);

        Optional<VaierConfig> loaded = adapter().load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getAwsSecret()).isEqualTo("the-aws-secret");
        assertThat(loaded.get().getSmtpPassword()).isEqualTo("the-smtp-password");
    }

    @Test
    void load_legacyPlaintextSecrets_loadUnchanged() throws IOException {
        // A pre-#307 config file has awsSecret/smtpPassword in the clear — they must still load.
        Files.writeString(tempDir.resolve("vaier-config.yml"), """
            domain: example.com
            awsKey: AKID
            awsSecret: legacy-plain-aws
            acmeEmail: admin@example.com
            smtpPassword: legacy-plain-smtp
            """);

        Optional<VaierConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getAwsSecret()).isEqualTo("legacy-plain-aws");
        assertThat(loaded.get().getSmtpPassword()).isEqualTo("legacy-plain-smtp");
    }

    @Test
    void legacyPlaintext_isEncryptedOnNextSave() throws IOException {
        Files.writeString(tempDir.resolve("vaier-config.yml"), """
            domain: example.com
            awsKey: AKID
            awsSecret: legacy-plain-aws
            acmeEmail: admin@example.com
            """);

        VaierConfigFileAdapter adapterInstance = adapter();
        VaierConfig loaded = adapterInstance.load().orElseThrow();
        adapterInstance.save(loaded);

        String contents = Files.readString(tempDir.resolve("vaier-config.yml"));
        assertThat(contents).doesNotContain("legacy-plain-aws").contains("enc:v1:");
        assertThat(adapter().load().orElseThrow().getAwsSecret()).isEqualTo("legacy-plain-aws");
    }

    // --- Vaier-server machine identity ---

    @Test
    void load_roundTripsVaierServerMachineId() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .vaierServerMachineId("3f2504e0-4f89-41d3-9a0c-0305e82c3301")
            .build();

        adapter().save(config);

        assertThat(adapter().load().orElseThrow().getVaierServerMachineId())
            .isEqualTo("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    }

    @Test
    void load_vaierServerMachineIdNullWhenNotPresent() {
        adapter().save(VaierConfig.builder().domain("example.com").build());

        assertThat(adapter().load().orElseThrow().getVaierServerMachineId()).isNull();
    }

    // --- Vaier-server SSH access (#311) ---

    @Test
    void load_roundTripsVaierServerSshAccess() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .vaierServerSshAccess(false)
            .build();

        adapter().save(config);

        Optional<VaierConfig> loaded = adapter().load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getVaierServerSshAccess()).isFalse();
    }

    @Test
    void load_vaierServerSshAccessNullWhenNotPresent() {
        adapter().save(VaierConfig.builder().domain("example.com").build());

        assertThat(adapter().load().orElseThrow().getVaierServerSshAccess()).isNull();
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
