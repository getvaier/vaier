package net.vaier.config;

import net.vaier.domain.DnsProvider;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
            .awsKey("fileKey")
            .awsSecret("fileSecret")
            .acmeEmail("file@example.com")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));

        ConfigResolver resolver = new ConfigResolver(configPersistence);

        assertThat(resolver.getDomain()).isEqualTo("file.com");
        assertThat(resolver.getAwsKey()).isEqualTo("fileKey");
        assertThat(resolver.getAwsSecret()).isEqualTo("fileSecret");
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
            .awsKey("k")
            .awsSecret("s")
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
            .awsKey(null)
            .awsSecret(null)
            .acmeEmail(null)
            .smtpHost("smtp.example.com")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));
        Map<String, String> env = Map.of(
            "VAIER_DOMAIN", "env.example.com",
            "VAIER_AWS_KEY", "envKey",
            "VAIER_AWS_SECRET", "envSecret",
            "ACME_EMAIL", "env@example.com"
        );

        ConfigResolver resolver = new ConfigResolver(configPersistence, env::get);

        assertThat(resolver.getDomain()).isEqualTo("env.example.com");
        assertThat(resolver.getAwsKey()).isEqualTo("envKey");
        assertThat(resolver.getAwsSecret()).isEqualTo("envSecret");
        assertThat(resolver.getAcmeEmail()).isEqualTo("env@example.com");
        assertThat(resolver.getSmtpHost()).isEqualTo("smtp.example.com");
    }

    @Test
    void fileValuesWinOverEnvVars() {
        VaierConfig config = VaierConfig.builder()
            .domain("file.com")
            .awsKey("fileKey")
            .awsSecret("fileSecret")
            .acmeEmail("file@example.com")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));
        Map<String, String> env = Map.of("VAIER_DOMAIN", "env.com");

        ConfigResolver resolver = new ConfigResolver(configPersistence, env::get);

        assertThat(resolver.getDomain()).isEqualTo("file.com");
    }

    @Test
    void socialAuthAvailable_isTrueOnlyWhenGoogleClientIdIsSet() {
        when(configPersistence.load()).thenReturn(Optional.empty());

        assertThat(new ConfigResolver(configPersistence, Map.<String, String>of()::get)
            .isSocialAuthAvailable()).isFalse();
        assertThat(new ConfigResolver(configPersistence,
            Map.of("VAIER_OIDC_GOOGLE_CLIENT_ID", "")::get).isSocialAuthAvailable()).isFalse();
        assertThat(new ConfigResolver(configPersistence,
            Map.of("VAIER_OIDC_GOOGLE_CLIENT_ID", "abc.apps.googleusercontent.com")::get)
            .isSocialAuthAvailable()).isTrue();
    }

    @Test
    void consoleAuthMode_defaultsToAutheliaWhenUnset() {
        when(configPersistence.load()).thenReturn(Optional.empty());

        assertThat(new ConfigResolver(configPersistence, Map.<String, String>of()::get)
            .getConsoleAuthMode()).isEqualTo(net.vaier.domain.AuthMode.AUTHELIA);
        assertThat(new ConfigResolver(configPersistence,
            Map.of("VAIER_CONSOLE_AUTH_MODE", "")::get)
            .getConsoleAuthMode()).isEqualTo(net.vaier.domain.AuthMode.AUTHELIA);
    }

    @Test
    void consoleAuthMode_readsSocialFromEnv() {
        when(configPersistence.load()).thenReturn(Optional.empty());

        assertThat(new ConfigResolver(configPersistence,
            Map.of("VAIER_CONSOLE_AUTH_MODE", "social")::get)
            .getConsoleAuthMode()).isEqualTo(net.vaier.domain.AuthMode.SOCIAL);
    }

    @Test
    void infersManualDnsProviderWhenAwsKeysAbsent() {
        when(configPersistence.load()).thenReturn(Optional.empty());

        ConfigResolver resolver = new ConfigResolver(configPersistence, key -> null);

        assertThat(resolver.getDnsProvider()).isEqualTo(DnsProvider.MANUAL);
    }

    @Test
    void infersRoute53DnsProviderWhenAwsKeysPresent() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("k")
            .awsSecret("s")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));

        ConfigResolver resolver = new ConfigResolver(configPersistence, key -> null);

        assertThat(resolver.getDnsProvider()).isEqualTo(DnsProvider.ROUTE53);
    }

    @Test
    void infersRoute53DnsProviderWhenOnlyEnvAwsKeysPresent() {
        when(configPersistence.load()).thenReturn(Optional.empty());
        Map<String, String> env = Map.of(
            "VAIER_AWS_KEY", "envKey",
            "VAIER_AWS_SECRET", "envSecret"
        );

        ConfigResolver resolver = new ConfigResolver(configPersistence, env::get);

        assertThat(resolver.getDnsProvider()).isEqualTo(DnsProvider.ROUTE53);
    }

    @Test
    void infersManualDnsProviderWhenOnlyAwsKeyPresentWithoutSecret() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("k")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));

        ConfigResolver resolver = new ConfigResolver(configPersistence, key -> null);

        assertThat(resolver.getDnsProvider()).isEqualTo(DnsProvider.MANUAL);
    }

    @Test
    void infersManualDnsProviderWhenAwsKeysBlank() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("   ")
            .awsSecret("   ")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));

        ConfigResolver resolver = new ConfigResolver(configPersistence, key -> null);

        assertThat(resolver.getDnsProvider()).isEqualTo(DnsProvider.MANUAL);
    }

    @Test
    void dnsProviderUpdatesAfterReloadPicksUpNewAwsKeys() {
        when(configPersistence.load()).thenReturn(Optional.empty());
        ConfigResolver resolver = new ConfigResolver(configPersistence, key -> null);
        assertThat(resolver.getDnsProvider()).isEqualTo(DnsProvider.MANUAL);

        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("k")
            .awsSecret("s")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));
        resolver.reload();

        assertThat(resolver.getDnsProvider()).isEqualTo(DnsProvider.ROUTE53);
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
