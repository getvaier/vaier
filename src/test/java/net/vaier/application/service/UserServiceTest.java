package net.vaier.application.service;

import net.vaier.domain.User;
import net.vaier.domain.port.ForPersistingUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
        service.addUser("alice", "password", "alice@example.com", "Alice", List.of("admins"));

        verify(forPersistingUsers).addUser("alice", "password", "alice@example.com", "Alice", List.of("admins"));
    }

    @Test
    void addUser_persistsCustomGroups() {
        service.addUser("alice", "password", "alice@example.com", "Alice", List.of("family", "media"));

        verify(forPersistingUsers).addUser("alice", "password", "alice@example.com", "Alice", List.of("family", "media"));
    }

    @Test
    void addUser_treatsNullGroupsAsEmpty() {
        service.addUser("alice", "password", "alice@example.com", "Alice", null);

        verify(forPersistingUsers).addUser("alice", "password", "alice@example.com", "Alice", List.of());
    }

    @Test
    void addUser_filtersBlankGroups() {
        service.addUser("alice", "password", "alice@example.com", "Alice",
                Arrays.asList("admins", "", null, "  ", "family"));

        verify(forPersistingUsers).addUser("alice", "password", "alice@example.com", "Alice",
                List.of("admins", "family"));
    }

    @Test
    void addUser_deduplicatesGroups() {
        service.addUser("alice", "password", "alice@example.com", "Alice",
                List.of("admins", "admins", "family"));

        verify(forPersistingUsers).addUser("alice", "password", "alice@example.com", "Alice",
                List.of("admins", "family"));
    }

    @Test
    void addUser_throwsWhenUserAlreadyExists() {
        doThrow(new RuntimeException("User already exists: alice"))
                .when(forPersistingUsers).addUser("alice", "password", "alice@example.com", "Alice", List.of());

        assertThatThrownBy(() -> service.addUser("alice", "password", "alice@example.com", "Alice", List.of()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void addUser_allowsNullDisplayname() {
        service.addUser("alice", "password", "alice@example.com", null, List.of());

        verify(forPersistingUsers).addUser("alice", "password", "alice@example.com", null, List.of());
    }

    // --- addUser: username validation ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void addUser_rejectsBlankUsername(String username) {
        assertThatThrownBy(() -> service.addUser(username, "password", "alice@example.com", "Alice", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");

        verifyNoInteractions(forPersistingUsers);
    }

    // --- addUser: password validation ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void addUser_rejectsBlankPassword(String password) {
        assertThatThrownBy(() -> service.addUser("alice", password, "alice@example.com", "Alice", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");

        verifyNoInteractions(forPersistingUsers);
    }

    @Test
    void addUser_rejectsPasswordShorterThanMinimum() {
        assertThatThrownBy(() -> service.addUser("alice", "short", "alice@example.com", "Alice", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");

        verifyNoInteractions(forPersistingUsers);
    }

    // --- addUser: email validation ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void addUser_rejectsBlankEmail(String email) {
        assertThatThrownBy(() -> service.addUser("alice", "password", email, "Alice", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");

        verifyNoInteractions(forPersistingUsers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "missing-at-sign.com", "@no-local.com", "no-domain@", "no-tld@foo"})
    void addUser_rejectsInvalidEmailFormat(String email) {
        assertThatThrownBy(() -> service.addUser("alice", "password", email, "Alice", List.of()))
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

    // --- updateEmail ---

    @Test
    void updateEmail_updatesEmail() {
        service.updateEmail("alice", "new@example.com");

        verify(forPersistingUsers).updateEmail("alice", "new@example.com");
    }

    @Test
    void updateEmail_throwsWhenUserNotFound() {
        doThrow(new RuntimeException("User not found: alice"))
                .when(forPersistingUsers).updateEmail("alice", "new@example.com");

        assertThatThrownBy(() -> service.updateEmail("alice", "new@example.com"))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void updateEmail_rejectsBlankUsername(String username) {
        assertThatThrownBy(() -> service.updateEmail(username, "new@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");

        verifyNoInteractions(forPersistingUsers);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void updateEmail_rejectsBlankEmail(String email) {
        assertThatThrownBy(() -> service.updateEmail("alice", email))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");

        verifyNoInteractions(forPersistingUsers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "missing-at-sign.com", "@no-local.com", "no-domain@", "no-tld@foo"})
    void updateEmail_rejectsInvalidEmailFormat(String email) {
        assertThatThrownBy(() -> service.updateEmail("alice", email))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");

        verifyNoInteractions(forPersistingUsers);
    }

    // --- updateDisplayName ---

    @Test
    void updateDisplayName_updatesDisplayName() {
        service.updateDisplayName("alice", "Alice Smith");

        verify(forPersistingUsers).updateDisplayName("alice", "Alice Smith");
    }

    @Test
    void updateDisplayName_throwsWhenUserNotFound() {
        doThrow(new RuntimeException("User not found: alice"))
                .when(forPersistingUsers).updateDisplayName("alice", "Alice Smith");

        assertThatThrownBy(() -> service.updateDisplayName("alice", "Alice Smith"))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void updateDisplayName_rejectsBlankUsername(String username) {
        assertThatThrownBy(() -> service.updateDisplayName(username, "Alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");

        verifyNoInteractions(forPersistingUsers);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void updateDisplayName_rejectsBlankDisplayName(String displayname) {
        assertThatThrownBy(() -> service.updateDisplayName("alice", displayname))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayname");

        verifyNoInteractions(forPersistingUsers);
    }

    // --- getUsers ---

    @Test
    void getUsers_delegatesToPort() {
        User user = mock(User.class);
        when(forPersistingUsers.getUsers()).thenReturn(List.of(user));

        assertThat(service.getUsers()).containsExactly(user);
    }

    @Test
    void getUsers_returnsEmptyListWhenNoUsers() {
        when(forPersistingUsers.getUsers()).thenReturn(List.of());

        assertThat(service.getUsers()).isEmpty();
    }

    // --- getGroups ---

    @Test
    void getGroups_returnsDistinctSortedGroupsAcrossUsers() {
        when(forPersistingUsers.getUsers()).thenReturn(List.of(
                new User("alice", "Alice", "a@e.com", List.of("admins", "family")),
                new User("bob", "Bob", "b@e.com", List.of("media", "admins")),
                new User("charlie", "Charlie", "c@e.com", List.of("family"))
        ));

        assertThat(service.getGroups()).containsExactly("admins", "family", "media");
    }

    @Test
    void getGroups_returnsEmptyListWhenNoUsers() {
        when(forPersistingUsers.getUsers()).thenReturn(List.of());

        assertThat(service.getGroups()).isEmpty();
    }

    @Test
    void getGroups_skipsBlankGroups() {
        when(forPersistingUsers.getUsers()).thenReturn(List.of(
                new User("alice", "Alice", "a@e.com", Arrays.asList("admins", "", null, "  ")),
                new User("bob", "Bob", "b@e.com", List.of("family"))
        ));

        assertThat(service.getGroups()).containsExactly("admins", "family");
    }

    @Test
    void getGroups_handlesUserWithNullGroups() {
        when(forPersistingUsers.getUsers()).thenReturn(List.of(
                new User("alice", "Alice", "a@e.com", null),
                new User("bob", "Bob", "b@e.com", List.of("family"))
        ));

        assertThat(service.getGroups()).containsExactly("family");
    }

    // --- updateUserGroups ---

    @Test
    void updateUserGroups_setsGroupsOnUser() {
        service.updateUserGroups("alice", List.of("admins", "family"));

        verify(forPersistingUsers).setUserGroups("alice", List.of("admins", "family"));
    }

    @Test
    void updateUserGroups_treatsNullAsEmpty() {
        service.updateUserGroups("alice", null);

        verify(forPersistingUsers).setUserGroups("alice", List.of());
    }

    @Test
    void updateUserGroups_filtersBlankAndDuplicates() {
        service.updateUserGroups("alice", Arrays.asList("admins", "", null, "admins", "family"));

        verify(forPersistingUsers).setUserGroups("alice", List.of("admins", "family"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void updateUserGroups_rejectsBlankUsername(String username) {
        assertThatThrownBy(() -> service.updateUserGroups(username, List.of("admins")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");

        verifyNoInteractions(forPersistingUsers);
    }

    @Test
    void updateUserGroups_throwsWhenUserNotFound() {
        doThrow(new RuntimeException("User not found: alice"))
                .when(forPersistingUsers).setUserGroups("alice", List.of("admins"));

        assertThatThrownBy(() -> service.updateUserGroups("alice", List.of("admins")))
                .isInstanceOf(RuntimeException.class);
    }

    // --- deleteGroup ---

    @Test
    void deleteGroup_removesGroupFromAllUsers() {
        when(forPersistingUsers.getUsers()).thenReturn(List.of(
                new User("alice", "Alice", "a@e.com", List.of("admins", "family")),
                new User("bob", "Bob", "b@e.com", List.of("family", "media"))
        ));

        service.deleteGroup("family");

        verify(forPersistingUsers).setUserGroups("alice", List.of("admins"));
        verify(forPersistingUsers).setUserGroups("bob", List.of("media"));
    }

    @Test
    void deleteGroup_skipsUsersWithoutTheGroup() {
        when(forPersistingUsers.getUsers()).thenReturn(List.of(
                new User("alice", "Alice", "a@e.com", List.of("admins", "family")),
                new User("bob", "Bob", "b@e.com", List.of("media"))
        ));

        service.deleteGroup("family");

        verify(forPersistingUsers).setUserGroups("alice", List.of("admins"));
        verify(forPersistingUsers, never()).setUserGroups(eq("bob"), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void deleteGroup_rejectsBlankGroupName(String groupName) {
        assertThatThrownBy(() -> service.deleteGroup(groupName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");

        verifyNoInteractions(forPersistingUsers);
    }
}
