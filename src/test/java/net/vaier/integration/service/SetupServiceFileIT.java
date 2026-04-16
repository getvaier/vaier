package net.vaier.integration.service;

import net.vaier.adapter.driven.AutheliaUserAdapter;
import net.vaier.adapter.driven.VaierConfigFileAdapter;
import net.vaier.application.service.LifecycleService;
import net.vaier.application.service.SetupService;
import net.vaier.config.SetupStateHolder;
import net.vaier.domain.User;
import net.vaier.domain.port.ForValidatingAwsCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Service+file integration tests: wires SetupService against real file adapters.
 * ForValidatingAwsCredentials and LifecycleService are mocked since they require
 * real AWS credentials / Docker socket respectively.
 */
class SetupServiceFileIT {

    @TempDir
    java.nio.file.Path tempDir;

    VaierConfigFileAdapter configAdapter;
    AutheliaUserAdapter userAdapter;
    SetupStateHolder stateHolder;
    SetupService setupService;

    ForValidatingAwsCredentials mockValidator = mock(ForValidatingAwsCredentials.class);
    LifecycleService mockLifecycle = mock(LifecycleService.class);

    @BeforeEach
    void setUp() {
        configAdapter = new VaierConfigFileAdapter(tempDir.resolve("vaier").toString());
        userAdapter = new AutheliaUserAdapter(
                tempDir.resolve("authelia").resolve("users_database.yml").toString());
        stateHolder = new SetupStateHolder(configAdapter);
        setupService = new SetupService(stateHolder, configAdapter, mockValidator, userAdapter, mockLifecycle);
    }

    @Test
    void completeSetup_writesConfigFileAndCreatesAdminUser() {
        setupService.completeSetup(
                "example.com", "AWS_KEY", "AWS_SECRET",
                "admin@example.com", "admin", "adminpass");

        assertThat(configAdapter.load()).isPresent();
        assertThat(configAdapter.load().get().getDomain()).isEqualTo("example.com");
        assertThat(configAdapter.load().get().getAwsKey()).isEqualTo("AWS_KEY");
        assertThat(configAdapter.load().get().getAcmeEmail()).isEqualTo("admin@example.com");

        List<User> users = userAdapter.getUsers();
        assertThat(users).hasSize(1);
        assertThat(users.getFirst().getName()).isEqualTo("admin");
    }

    @Test
    void completeSetup_setsStateHolderToConfigured() {
        assertThat(stateHolder.isConfigured()).isFalse();

        setupService.completeSetup(
                "example.com", "KEY", "SECRET", "email@example.com", "admin", "pass");

        assertThat(stateHolder.isConfigured()).isTrue();
    }

    @Test
    void completeSetup_triggersLifecycle() {
        setupService.completeSetup(
                "example.com", "KEY", "SECRET", "email@example.com", "admin", "pass");

        verify(mockLifecycle).runLifecycle();
    }

    @Test
    void completeSetup_throwsWhenAlreadyConfigured() {
        setupService.completeSetup(
                "example.com", "KEY", "SECRET", "email@example.com", "admin", "pass");

        assertThatThrownBy(() -> setupService.completeSetup(
                "example.com", "KEY", "SECRET", "email@example.com", "admin2", "pass2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already");
    }

    @Test
    void isConfigured_returnsFalseBeforeSetup() {
        assertThat(setupService.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_returnsTrueAfterSetup() {
        setupService.completeSetup(
                "example.com", "KEY", "SECRET", "email@example.com", "admin", "pass");

        assertThat(setupService.isConfigured()).isTrue();
    }

    @Test
    void validateAndListZones_delegatesToValidator() {
        when(mockValidator.listHostedZones("KEY", "SECRET")).thenReturn(List.of("vaier.net"));

        List<String> zones = setupService.validateAndListZones("KEY", "SECRET");

        assertThat(zones).containsExactly("vaier.net");
    }
}
