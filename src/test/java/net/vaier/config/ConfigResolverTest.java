package net.vaier.config;

import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigResolverTest {

    @Mock ForPersistingAppConfiguration configPersistence;

    @Test
    void resolvesFromFileWhenPresent() {
        VaierConfig config = VaierConfig.builder()
            .domain("file.com")
            .acmeEmail("file@example.com")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));

        ConfigResolver resolver = new ConfigResolver(configPersistence);

        assertThat(resolver.getDomain()).isEqualTo("file.com");
        assertThat(resolver.getAcmeEmail()).isEqualTo("file@example.com");
    }

    @Test
    void fallsBackToEnvVarsWhenNoFile() {
        when(configPersistence.load()).thenReturn(Optional.empty());

        ConfigResolver resolver = new ConfigResolver(configPersistence);

        // In test environment, env vars are not set, so values will be null
        // The important thing is it doesn't throw
        assertThat(resolver.getDomain()).isNull();
    }

    @Test
    void reloadPicksUpNewConfig() {
        when(configPersistence.load()).thenReturn(Optional.empty());
        ConfigResolver resolver = new ConfigResolver(configPersistence);
        assertThat(resolver.getDomain()).isNull();

        VaierConfig config = VaierConfig.builder()
            .domain("new.com")
            .acmeEmail("e@e.com")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));
        resolver.reload();

        assertThat(resolver.getDomain()).isEqualTo("new.com");
    }

    @Test
    void fallsBackToEnvVarPerFieldWhenPersistedValueIsNull() {
        VaierConfig config = VaierConfig.builder()
            .domain(null)
            .acmeEmail(null)
            .smtpHost("smtp.example.com")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));
        Map<String, String> env = Map.of(
            "VAIER_DOMAIN", "env.example.com",
            "ACME_EMAIL", "env@example.com"
        );

        ConfigResolver resolver = new ConfigResolver(configPersistence, env::get);

        assertThat(resolver.getDomain()).isEqualTo("env.example.com");
        assertThat(resolver.getAcmeEmail()).isEqualTo("env@example.com");
        assertThat(resolver.getSmtpHost()).isEqualTo("smtp.example.com");
    }

    @Test
    void fileValuesWinOverEnvVars() {
        VaierConfig config = VaierConfig.builder()
            .domain("file.com")
            .acmeEmail("file@example.com")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));
        Map<String, String> env = Map.of("VAIER_DOMAIN", "env.com");

        ConfigResolver resolver = new ConfigResolver(configPersistence, env::get);

        assertThat(resolver.getDomain()).isEqualTo("file.com");
    }

    @Test
    void socialAuthAvailable_isTrueOnceEitherProviderHasAClientId() {
        when(configPersistence.load()).thenReturn(Optional.empty());
        // #332 made each provider optional; a GitHub-only install signs in fine and must say so.
        record Row(Map<String, String> env, boolean available) {}
        for (Row row : List.of(
                new Row(Map.of(), false),
                new Row(Map.of("VAIER_OIDC_GOOGLE_CLIENT_ID", ""), false),
                new Row(Map.of("VAIER_OIDC_GOOGLE_CLIENT_ID", "abc.apps.googleusercontent.com"), true),
                new Row(Map.of("VAIER_OIDC_GITHUB_CLIENT_ID", "Iv1.abc"), true))) {
            assertThat(new ConfigResolver(configPersistence, row.env()::get).isSocialAuthAvailable())
                .as("%s", row.env()).isEqualTo(row.available());
        }
    }







    @Test
    void diskMonitorThreshold_defaultsTo85WhenUnset() {
        when(configPersistence.load()).thenReturn(Optional.empty());

        ConfigResolver resolver = new ConfigResolver(configPersistence, key -> null);

        assertThat(resolver.getDiskMonitorThresholdPercent()).isEqualTo(85);
    }

    @Test
    void diskMonitorThreshold_usesConfiguredValue() {
        VaierConfig config = VaierConfig.builder()
            .diskMonitorThresholdPercent(70)
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));

        ConfigResolver resolver = new ConfigResolver(configPersistence, key -> null);

        assertThat(resolver.getDiskMonitorThresholdPercent()).isEqualTo(70);
    }

    @Test
    void exposesBackupScheduleHour() {
        // Defaults to 2am when unset.
        when(configPersistence.load()).thenReturn(Optional.empty());
        assertThat(new ConfigResolver(configPersistence, key -> null).getBackupScheduleHour()).isEqualTo(2);

        // Uses the configured value when present.
        VaierConfig config = VaierConfig.builder().backupScheduleHour(5).build();
        when(configPersistence.load()).thenReturn(Optional.of(config));
        assertThat(new ConfigResolver(configPersistence, key -> null).getBackupScheduleHour()).isEqualTo(5);
    }

    @Test
    void treatsBlankPersistedValueAsMissing() {
        VaierConfig config = VaierConfig.builder()
            .domain("   ")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));
        Map<String, String> env = Map.of("VAIER_DOMAIN", "env.com");

        ConfigResolver resolver = new ConfigResolver(configPersistence, env::get);

        assertThat(resolver.getDomain()).isEqualTo("env.com");
    }
}
