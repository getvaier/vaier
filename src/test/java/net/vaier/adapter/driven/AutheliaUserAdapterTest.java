package net.vaier.adapter.driven;

import net.vaier.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutheliaUserAdapterTest {

    @TempDir Path tempDir;

    AutheliaUserAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AutheliaUserAdapter(tempDir.resolve("users_database.yml").toString());
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
}
