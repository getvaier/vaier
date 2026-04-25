package net.vaier.integration.adapter;

import net.vaier.adapter.driven.AutheliaUserAdapter;
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

/**
 * Integration tests for AutheliaUserAdapter against a real temp directory.
 * Covers multi-operation sequences that unit tests don't address.
 */
class AutheliaUserAdapterIT {

    @TempDir
    Path tempDir;

    AutheliaUserAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AutheliaUserAdapter(tempDir.resolve("users_database.yml").toString());
    }

    @Test
    void addUser_thenGetUsers_returnsAddedUser() {
        adapter.addUser("alice", "password123", "alice@example.com", "Alice Smith", List.of("admins"));

        List<User> users = adapter.getUsers();

        assertThat(users).hasSize(1);
        assertThat(users.getFirst().getName()).isEqualTo("alice");
        assertThat(users.getFirst().getEmail()).isEqualTo("alice@example.com");
        assertThat(users.getFirst().getDisplayname()).isEqualTo("Alice Smith");
        assertThat(users.getFirst().getGroups()).contains("admins");
    }

    @Test
    void addThreeUsers_deleteMiddleOne_remainingTwoSurvive() {
        adapter.addUser("alice", "pass1", "alice@example.com", "Alice", List.of("admins"));
        adapter.addUser("bob", "pass2", "bob@example.com", "Bob", List.of("admins"));
        adapter.addUser("charlie", "pass3", "charlie@example.com", "Charlie", List.of("admins"));

        adapter.deleteUser("bob");

        List<User> users = adapter.getUsers();
        assertThat(users).hasSize(2);
        assertThat(users).extracting(User::getName).containsExactlyInAnyOrder("alice", "charlie");
        assertThat(users).extracting(User::getName).doesNotContain("bob");
    }

    @Test
    void changePassword_updatesStoredHash() throws IOException {
        adapter.addUser("alice", "oldpassword", "alice@example.com", "Alice", List.of("admins"));

        String fileContentBefore = Files.readString(tempDir.resolve("users_database.yml"));

        adapter.changePassword("alice", "newpassword");

        String fileContentAfter = Files.readString(tempDir.resolve("users_database.yml"));
        assertThat(fileContentAfter).isNotEqualTo(fileContentBefore);
    }

    @Test
    void changePasswordTwice_secondHashDiffersFromFirst() throws IOException {
        adapter.addUser("alice", "original", "alice@example.com", "Alice", List.of("admins"));
        adapter.changePassword("alice", "first-new");
        String afterFirstChange = Files.readString(tempDir.resolve("users_database.yml"));

        adapter.changePassword("alice", "second-new");
        String afterSecondChange = Files.readString(tempDir.resolve("users_database.yml"));

        assertThat(afterSecondChange).isNotEqualTo(afterFirstChange);
    }

    @Test
    void addUser_autoCreatesParentDirectories() throws IOException {
        Path nestedDir = tempDir.resolve("authelia").resolve("config");
        AutheliaUserAdapter nestedAdapter = new AutheliaUserAdapter(
                nestedDir.resolve("users_database.yml").toString());

        nestedAdapter.addUser("alice", "pass", "alice@example.com", "Alice", List.of("admins"));

        assertThat(Files.exists(nestedDir.resolve("users_database.yml"))).isTrue();
        assertThat(nestedAdapter.getUsers()).hasSize(1);
    }

    @Test
    void addUser_throwsWhenUserAlreadyExists() {
        adapter.addUser("alice", "pass", "alice@example.com", "Alice", List.of("admins"));

        assertThatThrownBy(() -> adapter.addUser("alice", "pass2", "a@b.com", "Alice", List.of("admins")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("alice");
    }

    @Test
    void deleteUser_throwsWhenUserNotFound() {
        adapter.addUser("alice", "pass", "alice@example.com", "Alice", List.of("admins"));

        assertThatThrownBy(() -> adapter.deleteUser("bob"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("bob");
    }

    @Test
    void getUsers_returnsEmptyListWhenFileDoesNotExist() {
        // tempDir exists but no users_database.yml has been created yet
        assertThat(adapter.getUsers()).isEmpty();
    }

    @Test
    void isDatabaseInitialised_falseBeforeAdd_trueAfterAdd() {
        assertThat(adapter.isDatabaseInitialised()).isFalse();

        adapter.addUser("alice", "pass", "alice@example.com", "Alice", List.of("admins"));

        assertThat(adapter.isDatabaseInitialised()).isTrue();
    }

    @Test
    void multipleAddDelete_fileRemainsValidYaml() {
        adapter.addUser("a", "p1", "a@e.com", "A", List.of("admins"));
        adapter.addUser("b", "p2", "b@e.com", "B", List.of("admins"));
        adapter.addUser("c", "p3", "c@e.com", "C", List.of("admins"));
        adapter.deleteUser("a");
        adapter.deleteUser("c");
        adapter.addUser("d", "p4", "d@e.com", "D", List.of("admins"));

        List<User> users = adapter.getUsers();
        assertThat(users).hasSize(2);
        assertThat(users).extracting(User::getName).containsExactlyInAnyOrder("b", "d");
    }

    @Test
    void setUserGroups_thenGetUsers_reflectsNewGroups() {
        adapter.addUser("alice", "pass", "alice@example.com", "Alice", List.of("admins"));

        adapter.setUserGroups("alice", List.of("family", "media"));

        User alice = adapter.getUsers().getFirst();
        assertThat(alice.getGroups()).containsExactly("family", "media");
    }
}
