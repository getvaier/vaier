package net.vaier.application.service;

import net.vaier.config.ServiceNames;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForRestartingContainers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeleteUserServiceTest {

    @Mock
    ForPersistingUsers forPersistingUsers;

    @Mock
    ForRestartingContainers forRestartingContainers;

    @InjectMocks
    DeleteUserService service;

    @Test
    void deleteUser_restartsAutheliaAfterDeletingUser() {
        service.deleteUser("alice");

        verify(forPersistingUsers).deleteUser("alice");
        verify(forRestartingContainers).restartContainer(ServiceNames.AUTHELIA);
    }

    @Test
    void deleteUser_doesNotRestartContainerWhenDeleteUserFails() {
        doThrow(new RuntimeException("User not found: alice"))
                .when(forPersistingUsers).deleteUser("alice");

        assertThatThrownBy(() -> service.deleteUser("alice"))
                .isInstanceOf(RuntimeException.class);

        verify(forRestartingContainers, never()).restartContainer(any());
    }
}
