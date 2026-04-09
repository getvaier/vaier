package net.vaier.application.service;

import net.vaier.domain.port.ForPersistingUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    ForPersistingUsers forPersistingUsers;

    @InjectMocks
    UserService service;

    // --- addUser ---

    @Test
    void addUser_persistsUser() {
        service.addUser("alice", "password", "alice@example.com", "Alice");

        verify(forPersistingUsers).addUser("alice", "password", "alice@example.com", "Alice");
    }

    @Test
    void addUser_throwsWhenUserAlreadyExists() {
        doThrow(new RuntimeException("User already exists: alice"))
                .when(forPersistingUsers).addUser("alice", "password", "alice@example.com", "Alice");

        assertThatThrownBy(() -> service.addUser("alice", "password", "alice@example.com", "Alice"))
                .isInstanceOf(RuntimeException.class);
    }

    // --- deleteUser ---

    @Test
    void deleteUser_deletesUser() {
        service.deleteUser("alice");

        verify(forPersistingUsers).deleteUser("alice");
    }

    @Test
    void deleteUser_throwsWhenUserNotFound() {
        doThrow(new RuntimeException("User not found: alice"))
                .when(forPersistingUsers).deleteUser("alice");

        assertThatThrownBy(() -> service.deleteUser("alice"))
                .isInstanceOf(RuntimeException.class);
    }

    // --- changePassword ---

    @Test
    void changePassword_changesPassword() {
        service.changePassword("alice", "newpassword");

        verify(forPersistingUsers).changePassword("alice", "newpassword");
    }

    @Test
    void changePassword_throwsWhenUserNotFound() {
        doThrow(new RuntimeException("User not found: alice"))
                .when(forPersistingUsers).changePassword("alice", "newpassword");

        assertThatThrownBy(() -> service.changePassword("alice", "newpassword"))
                .isInstanceOf(RuntimeException.class);
    }
}
