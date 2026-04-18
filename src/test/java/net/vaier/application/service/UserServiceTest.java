package net.vaier.application.service;

import net.vaier.domain.port.ForPersistingUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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

    // --- addUser: happy path & delegation ---

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

    @Test
    void addUser_allowsNullDisplayname() {
        service.addUser("alice", "password", "alice@example.com", null);

        verify(forPersistingUsers).addUser("alice", "password", "alice@example.com", null);
    }

    // --- addUser: username validation ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void addUser_rejectsBlankUsername(String username) {
        assertThatThrownBy(() -> service.addUser(username, "password", "alice@example.com", "Alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");

        verifyNoInteractions(forPersistingUsers);
    }

    // --- addUser: password validation ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void addUser_rejectsBlankPassword(String password) {
        assertThatThrownBy(() -> service.addUser("alice", password, "alice@example.com", "Alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");

        verifyNoInteractions(forPersistingUsers);
    }

    @Test
    void addUser_rejectsPasswordShorterThanMinimum() {
        assertThatThrownBy(() -> service.addUser("alice", "short", "alice@example.com", "Alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");

        verifyNoInteractions(forPersistingUsers);
    }

    // --- addUser: email validation ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void addUser_rejectsBlankEmail(String email) {
        assertThatThrownBy(() -> service.addUser("alice", "password", email, "Alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");

        verifyNoInteractions(forPersistingUsers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "missing-at-sign.com", "@no-local.com", "no-domain@", "no-tld@foo"})
    void addUser_rejectsInvalidEmailFormat(String email) {
        assertThatThrownBy(() -> service.addUser("alice", "password", email, "Alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");

        verifyNoInteractions(forPersistingUsers);
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void deleteUser_rejectsBlankUsername(String username) {
        assertThatThrownBy(() -> service.deleteUser(username))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");

        verifyNoInteractions(forPersistingUsers);
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void changePassword_rejectsBlankUsername(String username) {
        assertThatThrownBy(() -> service.changePassword(username, "newpassword"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");

        verifyNoInteractions(forPersistingUsers);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void changePassword_rejectsBlankNewPassword(String newPassword) {
        assertThatThrownBy(() -> service.changePassword("alice", newPassword))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");

        verifyNoInteractions(forPersistingUsers);
    }

    @Test
    void changePassword_rejectsNewPasswordShorterThanMinimum() {
        assertThatThrownBy(() -> service.changePassword("alice", "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");

        verifyNoInteractions(forPersistingUsers);
    }
}
