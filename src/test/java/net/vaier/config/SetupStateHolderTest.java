package net.vaier.config;

import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SetupStateHolderTest {

    @Mock ForPersistingAppConfiguration configPersistence;

    @Test
    void isConfigured_returnsTrueWhenConfigFileExists() {
        when(configPersistence.exists()).thenReturn(true);

        SetupStateHolder holder = new SetupStateHolder(configPersistence);

        assertThat(holder.isConfigured()).isTrue();
    }

    @Test
    void isConfigured_returnsFalseWhenNoConfigFileAndNoEnvVars() {
        when(configPersistence.exists()).thenReturn(false);

        SetupStateHolder holder = new SetupStateHolder(configPersistence);

        // Without env vars set in test environment, should be false
        assertThat(holder.isConfigured()).isFalse();
    }

    @Test
    void markConfigured_setsConfiguredTrue() {
        when(configPersistence.exists()).thenReturn(false);

        SetupStateHolder holder = new SetupStateHolder(configPersistence);
        assertThat(holder.isConfigured()).isFalse();

        holder.markConfigured();
        assertThat(holder.isConfigured()).isTrue();
    }
}
