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
        adapter.addUser("alice", "password123", "alice@example.com", "Alice");
        adapter.addUser("bob", "password456", "bob@example.com", "Bob");

        List<User> users = adapter.getUsers();

        assertThat(users).extracting(User::getName)
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void getUsers_returnsDisplaynameEmailAndGroups() {
        adapter.addUser("alice", "password123", "alice@example.com", "Alice Example");

        List<User> users = adapter.getUsers();

        assertThat(users).hasSize(1);
        User alice = users.getFirst();
        assertThat(alice.getDisplayname()).isEqualTo("Alice Example");
        assertThat(alice.getEmail()).isEqualTo("alice@example.com");
        assertThat(alice.getGroups()).containsExactly("admins");
    }

    // --- addUser ---

    @Test
    void addUser_createsFileWithCorrectStructure() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice Example");

        List<User> users = adapter.getUsers();
        assertThat(users).hasSize(1);
        assertThat(users.getFirst().getName()).isEqualTo("alice");
    }

    @Test
    void addUser_hashesPasswordWithArgon2() throws IOException {
        adapter.addUser("alice", "mysecret", "alice@example.com", "Alice");

        String fileContent = Files.readString(tempDir.resolve("users_database.yml"));
        assertThat(fileContent).contains("$argon2id$");
        assertThat(fileContent).doesNotContain("mysecret");
    }

    @Test
    void addUser_placesUserInAdminsGroup() throws IOException {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice");

        String fileContent = Files.readString(tempDir.resolve("users_database.yml"));
        assertThat(fileContent).contains("admins");
    }

    @Test
    void addUser_usesUsernameAsDisplaynameWhenNull() throws IOException {
        adapter.addUser("alice", "secret", "alice@example.com", null);

        String fileContent = Files.readString(tempDir.resolve("users_database.yml"));
        assertThat(fileContent).contains("alice");
    }

    @Test
    void addUser_throwsWhenUserAlreadyExists() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice");

        assertThatThrownBy(() -> adapter.addUser("alice", "other", "other@example.com", "Alice2"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User already exists");
    }

    // --- deleteUser ---

    @Test
    void deleteUser_removesExistingUser() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice");
        adapter.addUser("bob", "secret", "bob@example.com", "Bob");

        adapter.deleteUser("alice");

        assertThat(adapter.getUsers()).extracting(User::getName).containsExactly("bob");
    }

    @Test
    void deleteUser_throwsWhenUserNotFound() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice");

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
        adapter.addUser("alice", "oldpassword", "alice@example.com", "Alice");
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
        adapter.addUser("alice", "secret", "alice@example.com", "Alice");

        assertThatThrownBy(() -> adapter.changePassword("nobody", "newpass"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }

    // --- updateEmail ---

    @Test
    void updateEmail_changesEmailForExistingUser() {
        adapter.addUser("alice", "secret", "old@example.com", "Alice");

        adapter.updateEmail("alice", "new@example.com");

        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void updateEmail_preservesPasswordAndOtherFields() throws IOException {
        adapter.addUser("alice", "secret", "old@example.com", "Alice");
        String before = Files.readString(tempDir.resolve("users_database.yml"));
        int hashIndex = before.indexOf("$argon2id$");
        String hashBefore = before.substring(hashIndex, before.indexOf('\n', hashIndex));

        adapter.updateEmail("alice", "new@example.com");

        String after = Files.readString(tempDir.resolve("users_database.yml"));
        assertThat(after).contains(hashBefore);
        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getDisplayname()).isEqualTo("Alice");
        assertThat(alice.getGroups()).containsExactly("admins");
    }

    @Test
    void updateEmail_throwsWhenUserNotFound() {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice");

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
        adapter.addUser("alice", "secret", "alice@example.com", "Old Name");

        adapter.updateDisplayName("alice", "New Name");

        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getDisplayname()).isEqualTo("New Name");
    }

    @Test
    void updateDisplayName_preservesPasswordAndEmail() throws IOException {
        adapter.addUser("alice", "secret", "alice@example.com", "Old Name");
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
        adapter.addUser("alice", "secret", "alice@example.com", "Alice");

        assertThatThrownBy(() -> adapter.updateDisplayName("nobody", "New"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void updateDisplayName_throwsWhenFileDoesNotExist() {
        assertThatThrownBy(() -> adapter.updateDisplayName("alice", "New"))
                .isInstanceOf(RuntimeException.class);
    }

    // --- secret file permissions ---

    @Test
    void addUser_writesUsersFileWithOwnerOnlyPermissions() throws IOException {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tempDir.resolve("users_database.yml"));
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void changePassword_keepsUsersFileLockedDown() throws IOException {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice");

        adapter.changePassword("alice", "newsecret");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tempDir.resolve("users_database.yml"));
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void deleteUser_keepsUsersFileLockedDown() throws IOException {
        adapter.addUser("alice", "secret", "alice@example.com", "Alice");
        adapter.addUser("bob", "secret", "bob@example.com", "Bob");

        adapter.deleteUser("bob");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tempDir.resolve("users_database.yml"));
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }
}
