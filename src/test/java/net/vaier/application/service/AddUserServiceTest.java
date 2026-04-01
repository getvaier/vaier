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
class AddUserServiceTest {

    @Mock
    ForPersistingUsers forPersistingUsers;

    @Mock
    ForRestartingContainers forRestartingContainers;

    @InjectMocks
    AddUserService service;

    @Test
    void addUser_restartsAutheliaAfterAddingUser() {
        service.addUser("alice", "password", "alice@example.com", "Alice");

        verify(forPersistingUsers).addUser("alice", "password", "alice@example.com", "Alice");
        verify(forRestartingContainers).restartContainer(ServiceNames.AUTHELIA);
    }

    @Test
    void addUser_doesNotRestartContainerWhenAddUserFails() {
        doThrow(new RuntimeException("User already exists: alice"))
                .when(forPersistingUsers).addUser("alice", "password", "alice@example.com", "Alice");

        assertThatThrownBy(() -> service.addUser("alice", "password", "alice@example.com", "Alice"))
                .isInstanceOf(RuntimeException.class);

        verify(forRestartingContainers, never()).restartContainer(any());
    }
}
