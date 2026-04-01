package net.vaier.application.service;

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
class ChangePasswordServiceTest {

    @Mock
    ForPersistingUsers forPersistingUsers;

    @Mock
    ForRestartingContainers forRestartingContainers;

    @InjectMocks
    ChangePasswordService service;

    @Test
    void changePassword_restartsAutheliaAfterUpdatingPassword() {
        service.changePassword("alice", "newpassword");

        verify(forPersistingUsers).changePassword("alice", "newpassword");
        verify(forRestartingContainers).restartContainer("authelia");
    }

    @Test
    void changePassword_doesNotRestartContainerWhenPasswordChangeFails() {
        doThrow(new RuntimeException("User not found: alice"))
                .when(forPersistingUsers).changePassword("alice", "newpassword");

        assertThatThrownBy(() -> service.changePassword("alice", "newpassword"))
                .isInstanceOf(RuntimeException.class);

        verify(forRestartingContainers, never()).restartContainer(any());
    }
}
