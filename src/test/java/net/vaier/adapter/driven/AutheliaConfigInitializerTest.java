package net.vaier.adapter.driven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class AutheliaConfigInitializerTest {

    @TempDir Path tempDir;

    // --- initialiseConfiguration ---

    @Test
    void initialiseConfiguration_createsConfigFile() {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");

        init.initialiseConfiguration();

        assertThat(tempDir.resolve("configuration.yml")).exists();
    }

    @Test
    void initialiseConfiguration_injectsCorrectBaseDomain() throws IOException {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");

        init.initialiseConfiguration();

        String content = Files.readString(tempDir.resolve("configuration.yml"));
        assertThat(content).contains("domain: example.com");
        assertThat(content).contains("authelia_url: https://vaier.example.com");
    }

    @Test
    void initialiseConfiguration_injectsFullVaierDomainForAccessControl() throws IOException {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");

        init.initialiseConfiguration();

        String content = Files.readString(tempDir.resolve("configuration.yml"));
        assertThat(content).contains("vaier.example.com");
    }

    @Test
    void initialiseConfiguration_generatesSecretsFile() {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");

        init.initialiseConfiguration();

        assertThat(tempDir.resolve("secrets.properties")).exists();
    }

    @Test
    void initialiseConfiguration_reusesSecretsOnSecondCall() throws IOException {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");

        init.initialiseConfiguration();
        String firstConfig = Files.readString(tempDir.resolve("configuration.yml"));

        init.initialiseConfiguration();
        String secondConfig = Files.readString(tempDir.resolve("configuration.yml"));

        // Secrets (jwt, session, encryption key) should be identical across calls
        assertThat(firstConfig).isEqualTo(secondConfig);
    }

    @Test
    void initialiseConfiguration_handlesSubdomain() throws IOException {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "sub.example.com");

        init.initialiseConfiguration();

        String content = Files.readString(tempDir.resolve("configuration.yml"));
        assertThat(content).contains("domain: sub.example.com");
        assertThat(content).contains("vaier.sub.example.com");
    }

    @Test
    void initialiseConfiguration_configContainsRequiredSections() throws IOException {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");

        init.initialiseConfiguration();

        String content = Files.readString(tempDir.resolve("configuration.yml"));
        assertThat(content).contains("authentication_backend:");
        assertThat(content).contains("session:");
        assertThat(content).contains("storage:");
        assertThat(content).contains("access_control:");
        assertThat(content).contains("notifier:");
    }

    @Test
    void initialiseConfiguration_launchpadPathsBypassAuthentication() throws IOException {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");

        init.initialiseConfiguration();

        String content = Files.readString(tempDir.resolve("configuration.yml"));
        assertThat(content).contains("policy: bypass");
        assertThat(content).contains("/launchpad.html");
        assertThat(content).contains("/published-services/discover");
    }

    @Test
    void initialiseConfiguration_faviconPathsBypassAuthForAllSubdomains() throws IOException {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");

        init.initialiseConfiguration();

        String content = Files.readString(tempDir.resolve("configuration.yml"));
        assertThat(content).contains("domain_regex");
        assertThat(content).contains("example\\.com");  // dots escaped for regex
        assertThat(content).contains("favicon\\.ico");
        assertThat(content).contains("apple-touch-icon");
    }

    @Test
    void initialiseConfiguration_vaierDomainRequiresOneFactorByDefault() throws IOException {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");

        init.initialiseConfiguration();

        String content = Files.readString(tempDir.resolve("configuration.yml"));
        assertThat(content).contains("policy: one_factor");
        // bypass rule for launchpad must appear before the one_factor rule
        int bypassIndex = content.indexOf("policy: bypass");
        int oneFactorIndex = content.lastIndexOf("policy: one_factor");
        assertThat(bypassIndex).isLessThan(oneFactorIndex);
    }

    @Test
    void initialiseConfiguration_returnsTrueWhenFileIsCreated() {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");

        boolean result = init.initialiseConfiguration();

        assertThat(result).isTrue();
    }

    @Test
    void initialiseConfiguration_returnsFalseWhenConfigUnchanged() {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");
        init.initialiseConfiguration();

        boolean result = init.initialiseConfiguration();

        assertThat(result).isFalse();
    }

    @Test
    void initialiseConfiguration_usesFilesystemNotifierByDefault() throws IOException {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");

        init.initialiseConfiguration();

        String content = Files.readString(tempDir.resolve("configuration.yml"));
        assertThat(content).contains("filesystem:");
        assertThat(content).contains("/config/emails.txt");
    }

    @Test
    void updateSmtpConfig_regeneratesConfigWithSmtpNotifier() throws IOException {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");
        init.initialiseConfiguration();

        init.updateSmtpConfig("smtp.example.com", 587, "user@example.com", "password123", "noreply@example.com");

        String content = Files.readString(tempDir.resolve("configuration.yml"));
        assertThat(content).contains("smtp:");
        assertThat(content).contains("smtp://smtp.example.com:587");
        assertThat(content).contains("user@example.com");
        assertThat(content).contains("noreply@example.com");
        assertThat(content).doesNotContain("filesystem:");
    }

    @Test
    void updateSmtpConfig_writesPasswordToSecretsFile() throws IOException {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");

        init.updateSmtpConfig("smtp.example.com", 587, "user@example.com", "mypassword", "noreply@example.com");

        Properties props = new Properties();
        try (var fis = new java.io.FileInputStream(tempDir.resolve("secrets.properties").toFile())) {
            props.load(fis);
        }
        assertThat(props.getProperty("smtp_password")).isEqualTo("mypassword");
    }

    @Test
    void updateSmtpConfig_preservesExistingSecrets() throws IOException {
        AutheliaConfigInitializer init = new AutheliaConfigInitializer(tempDir.toString(), "example.com");
        init.initialiseConfiguration(); // generates jwt_secret, session_secret, encryption_key

        init.updateSmtpConfig("smtp.example.com", 587, "user@example.com", "pass", "sender@example.com");

        Properties props = new Properties();
        try (var fis = new java.io.FileInputStream(tempDir.resolve("secrets.properties").toFile())) {
            props.load(fis);
        }
        assertThat(props.getProperty("jwt_secret")).isNotBlank();
        assertThat(props.getProperty("session_secret")).isNotBlank();
        assertThat(props.getProperty("encryption_key")).isNotBlank();
        assertThat(props.getProperty("smtp_password")).isEqualTo("pass");
    }
}
