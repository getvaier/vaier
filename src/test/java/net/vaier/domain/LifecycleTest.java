package net.vaier.domain;

import net.vaier.config.ServiceNames;
import net.vaier.domain.port.ForInitialisingUserService;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForRestartingContainers;
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

    private Lifecycle lifecycle() {
        return new Lifecycle(forInitialisingUserService, forPersistingUsers, forPersistingDnsRecords, containerRestarter, "test.example.com");
    }

    @Test
    void initUsers_createsAdminWithRandomPasswordOnFirstStartup() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(false);

        lifecycle().initUsers();

        ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
        verify(forPersistingUsers).addUser(eq(ServiceNames.DEFAULT_ADMIN_USERNAME), passwordCaptor.capture(), any(), any());
        assertThat(passwordCaptor.getValue()).isNotEqualTo(ServiceNames.DEFAULT_ADMIN_USERNAME);
        assertThat(passwordCaptor.getValue()).hasSizeGreaterThanOrEqualTo(12);
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

        verify(containerRestarter).restartContainer(ServiceNames.AUTHELIA);
    }

    @Test
    void initUsers_restartsAutheliaWhenConfigChanged() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(true);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(true);

        lifecycle().initUsers();

        verify(containerRestarter).restartContainer(ServiceNames.AUTHELIA);
    }

    @Test
    void initUsers_doesNotRestartAutheliaWhenNothingChanged() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(true);

        lifecycle().initUsers();

        verify(containerRestarter, never()).restartContainer(any());
    }
}
