package net.vaier.application.service;

import net.vaier.config.SetupStateHolder;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForValidatingAwsCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SetupServiceTest {

    @Mock SetupStateHolder setupStateHolder;
    @Mock ForPersistingAppConfiguration configPersistence;
    @Mock ForValidatingAwsCredentials forValidatingAwsCredentials;
    @Mock ForPersistingUsers forPersistingUsers;
    @Mock LifecycleService lifecycleService;
    @InjectMocks SetupService setupService;

    @Test
    void isConfigured_delegatesToSetupStateHolder() {
        when(setupStateHolder.isConfigured()).thenReturn(true);
        assertThat(setupService.isConfigured()).isTrue();
    }

    @Test
    void validateAndListZones_delegatesToPort() {
        when(forValidatingAwsCredentials.listHostedZones("key", "secret"))
            .thenReturn(List.of("example.com", "other.org"));

        List<String> zones = setupService.validateAndListZones("key", "secret");

        assertThat(zones).containsExactly("example.com", "other.org");
    }

    @Test
    void completeSetup_savesConfigAndCreatesAdmin() {
        setupService.completeSetup("example.com", "key", "secret", "admin@example.com", "admin", "password123");

        ArgumentCaptor<VaierConfig> configCaptor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(configCaptor.capture());
        VaierConfig saved = configCaptor.getValue();
        assertThat(saved.getDomain()).isEqualTo("example.com");
        assertThat(saved.getAwsKey()).isEqualTo("key");
        assertThat(saved.getAwsSecret()).isEqualTo("secret");
        assertThat(saved.getAcmeEmail()).isEqualTo("admin@example.com");

        verify(setupStateHolder).markConfigured();
        verify(forPersistingUsers).addUser("admin", "password123", "", "admin");
    }

    @Test
    void completeSetup_triggersLifecycle() {
        setupService.completeSetup("example.com", "key", "secret", "admin@example.com", "admin", "pass");

        verify(lifecycleService).runLifecycle();
    }

    @Test
    void completeSetup_throwsWhenAlreadyConfigured() {
        when(setupStateHolder.isConfigured()).thenReturn(true);

        assertThatThrownBy(() ->
            setupService.completeSetup("example.com", "key", "secret", "admin@example.com", "admin", "pass")
        ).isInstanceOf(IllegalStateException.class);
    }
}
