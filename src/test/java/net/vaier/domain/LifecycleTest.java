package net.vaier.domain;

import net.vaier.domain.port.ForInitialisingUserService;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForRestartingContainers;
import net.vaier.domain.port.ForWritingBootstrapCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LifecycleTest {

    @Mock ForInitialisingUserService forInitialisingUserService;
    @Mock ForPersistingUsers forPersistingUsers;
    @Mock ForPersistingDnsRecords forPersistingDnsRecords;
    @Mock ForRestartingContainers containerRestarter;
    @Mock ForWritingBootstrapCredentials bootstrapCredentialsWriter;

    private Lifecycle lifecycle() {
        return new Lifecycle(forInitialisingUserService, forPersistingUsers, forPersistingDnsRecords, containerRestarter,
                bootstrapCredentialsWriter,
                "test.example.com", "admin", "authelia", "vaier", "login");
    }

    @Test
    void initUsers_createsAdminWithRandomPasswordOnFirstStartup() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(false);

        lifecycle().initUsers();

        ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
        verify(forPersistingUsers).addUser(eq("admin"), passwordCaptor.capture(), any(), any());
        assertThat(passwordCaptor.getValue()).isNotEqualTo("admin");
        assertThat(passwordCaptor.getValue()).hasSizeGreaterThanOrEqualTo(12);
    }

    @Test
    void initUsers_writesBootstrapPasswordToFileInsteadOfLogging() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(false);

        lifecycle().initUsers();

        ArgumentCaptor<String> usernameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
        verify(bootstrapCredentialsWriter)
            .writeBootstrapPassword(usernameCaptor.capture(), passwordCaptor.capture());
        assertThat(usernameCaptor.getValue()).isEqualTo("admin");
        assertThat(passwordCaptor.getValue()).hasSizeGreaterThanOrEqualTo(12);
    }

    @Test
    void initUsers_doesNotWriteBootstrapPasswordWhenAdminAlreadyExists() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(true);

        lifecycle().initUsers();

        verify(bootstrapCredentialsWriter, never()).writeBootstrapPassword(any(), any());
    }

    @Test
    void initUsers_doesNotCreateAdminOnSubsequentStartup() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(true);

        lifecycle().initUsers();

        verify(forPersistingUsers, never()).addUser(any(), any(), any(), any());
    }

    @Test
    void initUsers_restartsAutheliaWhenAdminIsCreated() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(false);

        lifecycle().initUsers();

        verify(containerRestarter).restartContainer("authelia");
    }

    @Test
    void initUsers_restartsAutheliaWhenConfigChanged() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(true);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(true);

        lifecycle().initUsers();

        verify(containerRestarter).restartContainer("authelia");
    }

    @Test
    void initUsers_doesNotRestartAutheliaWhenNothingChanged() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(true);

        lifecycle().initUsers();

        verify(containerRestarter, never()).restartContainer(any());
    }
}
