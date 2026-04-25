package net.vaier.adapter.driven;

import net.vaier.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutheliaUserAdapterTest {

    @TempDir Path tempDir;

    AutheliaUserAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AutheliaUserAdapter(tempDir.resolve("users_database.yml").toString());
    }

    // --- isDatabaseInitialised ---

    @Test
    void isDatabaseInitialised_returnsFalseWhenFileDoesNotExist() {
        assertThat(adapter.isDatabaseInitialised()).isFalse();
    }

    @Test
    void isDatabaseInitialised_returnsTrueWhenFileExists() throws IOException {
        Files.createFile(tempDir.resolve("users_database.yml"));

        assertThat(adapter.isDatabaseInitialised()).isTrue();
    }

    // --- getUsers ---

    @Test
    void getUsers_returnsEmptyListWhenFileDoesNotExist() {
        assertThat(adapter.getUsers()).isEmpty();
    }

    @Test
    void getUsers_returnsEmptyListWhenFileIsEmpty() throws IOException {
        Files.createFile(tempDir.resolve("users_database.yml"));
        assertThat(adapter.getUsers()).isEmpty();
    }

    @Test
    void getUsers_returnsUsersFromFile() {
        adapter.addUser("alice", "password123", "alice@example.com", "Alice", List.of("admins"));
        adapter.addUser("bob", "password456", "bob@example.com", "Bob", List.of("family"));

        List<User> users = adapter.getUsers();

        assertThat(users).extracting(User::getName)
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void getUsers_returnsDisplaynameEmailAndGroups() {
        adapter.addUser("alice", "password123", "alice@example.com", "Alice Example", List.of("admins", "family"));

        List<User> users = adapter.getUsers();

        assertThat(users).hasSize(1);
        User alice = users.getFirst();
        assertThat(alice.getDisplayname()).isEqualTo("Alice Example");
        assertThat(alice.getEmail()).isEqualTo("alice@example.com");
        assertThat(alice.getGroups()).containsExactly("admins", "family");
    }

    // --- addUser ---

    @Test
    void addUser_createsFileWithCorrectStructure() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice Example", List.of("admins"));

        List<User> users = adapter.getUsers();
        assertThat(users).hasSize(1);
        assertThat(users.getFirst().getName()).isEqualTo("alice");
    }

    @Test
    void addUser_hashesPasswordWithArgon2() throws IOException {
        adapter.addUser("alice", "mysecret", "alice@example.com", "Alice", List.of("admins"));

        String fileContent = Files.readString(tempDir.resolve("users_database.yml"));
        assertThat(fileContent).contains("$argon2id$");
        assertThat(fileContent).doesNotContain("mysecret");
    }

    @Test
    void addUser_persistsProvidedGroups() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("family", "media"));

        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getGroups()).containsExactly("family", "media");
    }

    @Test
    void addUser_persistsEmptyGroups() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of());

        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getGroups()).isEmpty();
    }

    @Test
    void addUser_treatsNullGroupsAsEmpty() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", null);

        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getGroups()).isEmpty();
    }

    @Test
    void addUser_usesUsernameAsDisplaynameWhenNull() throws IOException {
        adapter.addUser("alice", "secret", "alice@example.com", null, List.of("admins"));

        String fileContent = Files.readString(tempDir.resolve("users_database.yml"));
        assertThat(fileContent).contains("alice");
    }

    @Test
    void addUser_throwsWhenUserAlreadyExists() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));

        assertThatThrownBy(() -> adapter.addUser("alice", "other", "other@example.com", "Alice2", List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User already exists");
    }

    // --- deleteUser ---

    @Test
    void deleteUser_removesExistingUser() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));
        adapter.addUser("bob", "secret", "bob@example.com", "Bob", List.of("admins"));

        adapter.deleteUser("alice");

        assertThat(adapter.getUsers()).extracting(User::getName).containsExactly("bob");
    }

    @Test
    void deleteUser_throwsWhenUserNotFound() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));

        assertThatThrownBy(() -> adapter.deleteUser("nobody"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void deleteUser_throwsWhenFileDoesNotExist() {
        assertThatThrownBy(() -> adapter.deleteUser("alice"))
                .isInstanceOf(RuntimeException.class);
    }

    // --- changePassword ---

    @Test
    void changePassword_updatesPasswordHash() throws IOException {
        adapter.addUser("alice", "oldpassword", "alice@example.com", "Alice", List.of("admins"));
        String before = Files.readString(tempDir.resolve("users_database.yml"));

        adapter.changePassword("alice", "newpassword");

        String after = Files.readString(tempDir.resolve("users_database.yml"));
        assertThat(after).contains("$argon2id$");
        assertThat(after).doesNotContain("newpassword");
        // The hash should have changed
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void changePassword_throwsWhenUserNotFound() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));

        assertThatThrownBy(() -> adapter.changePassword("nobody", "newpass"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }

    // --- updateEmail ---

    @Test
    void updateEmail_changesEmailForExistingUser() {
        adapter.addUser("alice", "secret", "old@example.com", "Alice", List.of("admins"));

        adapter.updateEmail("alice", "new@example.com");

        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void updateEmail_preservesPasswordAndOtherFields() throws IOException {
        adapter.addUser("alice", "secret", "old@example.com", "Alice", List.of("admins", "family"));
        String before = Files.readString(tempDir.resolve("users_database.yml"));
        int hashIndex = before.indexOf("$argon2id$");
        String hashBefore = before.substring(hashIndex, before.indexOf('\n', hashIndex));

        adapter.updateEmail("alice", "new@example.com");

        String after = Files.readString(tempDir.resolve("users_database.yml"));
        assertThat(after).contains(hashBefore);
        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getDisplayname()).isEqualTo("Alice");
        assertThat(alice.getGroups()).containsExactly("admins", "family");
    }

    @Test
    void updateEmail_throwsWhenUserNotFound() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));

        assertThatThrownBy(() -> adapter.updateEmail("nobody", "x@example.com"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void updateEmail_throwsWhenFileDoesNotExist() {
        assertThatThrownBy(() -> adapter.updateEmail("alice", "x@example.com"))
                .isInstanceOf(RuntimeException.class);
    }

    // --- updateDisplayName ---

    @Test
    void updateDisplayName_changesDisplayNameForExistingUser() {
        adapter.addUser("alice", "secret", "alice@example.com", "Old Name", List.of("admins"));

        adapter.updateDisplayName("alice", "New Name");

        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getDisplayname()).isEqualTo("New Name");
    }

    @Test
    void updateDisplayName_preservesPasswordAndEmail() throws IOException {
        adapter.addUser("alice", "secret", "alice@example.com", "Old Name", List.of("admins"));
        String before = Files.readString(tempDir.resolve("users_database.yml"));
        int hashIndex = before.indexOf("$argon2id$");
        String hashBefore = before.substring(hashIndex, before.indexOf('\n', hashIndex));

        adapter.updateDisplayName("alice", "New Name");

        String after = Files.readString(tempDir.resolve("users_database.yml"));
        assertThat(after).contains(hashBefore);
        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void updateDisplayName_throwsWhenUserNotFound() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));

        assertThatThrownBy(() -> adapter.updateDisplayName("nobody", "New"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void updateDisplayName_throwsWhenFileDoesNotExist() {
        assertThatThrownBy(() -> adapter.updateDisplayName("alice", "New"))
                .isInstanceOf(RuntimeException.class);
    }

    // --- setUserGroups ---

    @Test
    void setUserGroups_replacesGroupsForExistingUser() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));

        adapter.setUserGroups("alice", List.of("family", "media"));

        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getGroups()).containsExactly("family", "media");
    }

    @Test
    void setUserGroups_canSetEmptyList() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));

        adapter.setUserGroups("alice", List.of());

        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getGroups()).isEmpty();
    }

    @Test
    void setUserGroups_preservesOtherFields() throws IOException {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));
        String before = Files.readString(tempDir.resolve("users_database.yml"));
        int hashIndex = before.indexOf("$argon2id$");
        String hashBefore = before.substring(hashIndex, before.indexOf('\n', hashIndex));

        adapter.setUserGroups("alice", List.of("family"));

        String after = Files.readString(tempDir.resolve("users_database.yml"));
        assertThat(after).contains(hashBefore);
        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getDisplayname()).isEqualTo("Alice");
        assertThat(alice.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void setUserGroups_throwsWhenUserNotFound() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));

        assertThatThrownBy(() -> adapter.setUserGroups("nobody", List.of("admins")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }

    // --- secret file permissions ---

    @Test
    void addUser_writesUsersFileWithOwnerOnlyPermissions() throws IOException {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tempDir.resolve("users_database.yml"));
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void changePassword_keepsUsersFileLockedDown() throws IOException {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));

        adapter.changePassword("alice", "newsecret");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tempDir.resolve("users_database.yml"));
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void deleteUser_keepsUsersFileLockedDown() throws IOException {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));
        adapter.addUser("bob", "secret", "bob@example.com", "Bob", List.of("admins"));

        adapter.deleteUser("bob");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tempDir.resolve("users_database.yml"));
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }
}
