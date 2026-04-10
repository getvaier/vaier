package net.vaier.config;

import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
