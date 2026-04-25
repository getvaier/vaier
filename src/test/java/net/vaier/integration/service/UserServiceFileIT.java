package net.vaier.integration.service;

import net.vaier.adapter.driven.AutheliaUserAdapter;
import net.vaier.application.service.UserService;
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
 * Service+file integration tests: wires UserService against a real AutheliaUserAdapter.
 * No Spring context or mocks — tests pure I/O round-trips through the use-case boundary.
 */
class UserServiceFileIT {

    @TempDir
    Path tempDir;

    UserService userService;
    AutheliaUserAdapter userAdapter;

    @BeforeEach
    void setUp() {
        userAdapter = new AutheliaUserAdapter(tempDir.resolve("users_database.yml").toString());
        userService = new UserService(userAdapter);
    }

    @Test
    void addUser_thenDeleteUser_fileReflectsOnlyRemainingUser() {
        userService.addUser("alice", "password1", "alice@example.com", "Alice", List.of("admins"));
        userService.addUser("bob", "password2", "bob@example.com", "Bob", List.of("admins"));

        userService.deleteUser("alice");

        List<User> users = userAdapter.getUsers();
        assertThat(users).hasSize(1);
        assertThat(users.getFirst().getName()).isEqualTo("bob");
    }

    @Test
    void addUser_thenChangePassword_hashDiffersInFile() throws IOException {
        userService.addUser("alice", "original", "alice@example.com", "Alice", List.of("admins"));
        String hashBefore = extractHashFromFile(tempDir.resolve("users_database.yml"), "alice");

        userService.changePassword("alice", "newpassword");
        String hashAfter = extractHashFromFile(tempDir.resolve("users_database.yml"), "alice");

        assertThat(hashAfter).isNotEqualTo(hashBefore);
    }

    @Test
    void deleteUser_nonExistent_throwsRuntimeException() {
        userService.addUser("alice", "password", "alice@example.com", "Alice", List.of("admins"));

        assertThatThrownBy(() -> userService.deleteUser("bob"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void addDuplicateUser_throwsRuntimeException() {
        userService.addUser("alice", "password1", "alice@example.com", "Alice", List.of("admins"));

        assertThatThrownBy(() -> userService.addUser("alice", "password2", "a@b.com", "Alice2", List.of()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void addUser_persistsCustomGroups() {
        userService.addUser("alice", "password", "alice@example.com", "Alice", List.of("family", "media"));

        User alice = userAdapter.getUsers().getFirst();
        assertThat(alice.getGroups()).containsExactly("family", "media");
    }

    @Test
    void getGroups_returnsDistinctGroupsAcrossUsers() {
        userService.addUser("alice", "password", "alice@example.com", "Alice", List.of("admins", "family"));
        userService.addUser("bob", "password", "bob@example.com", "Bob", List.of("media", "family"));

        assertThat(userService.getGroups()).containsExactly("admins", "family", "media");
    }

    @Test
    void updateUserGroups_thenGetUsers_reflectsNewGroups() {
        userService.addUser("alice", "password", "alice@example.com", "Alice", List.of("admins"));

        userService.updateUserGroups("alice", List.of("family", "media"));

        User alice = userAdapter.getUsers().getFirst();
        assertThat(alice.getGroups()).containsExactly("family", "media");
    }

    @Test
    void deleteGroup_stripsGroupFromAllUsers() {
        userService.addUser("alice", "password", "alice@example.com", "Alice", List.of("admins", "family"));
        userService.addUser("bob", "password", "bob@example.com", "Bob", List.of("family", "media"));

        userService.deleteGroup("family");

        assertThat(userAdapter.getUsers()).extracting(User::getGroups)
                .containsExactlyInAnyOrder(List.of("admins"), List.of("media"));
        assertThat(userService.getGroups()).containsExactly("admins", "media");
    }

    private String extractHashFromFile(Path file, String username) throws IOException {
        String content = Files.readString(file);
        String[] lines = content.split("\n");
        boolean inUserSection = false;
        for (String line : lines) {
            if (line.trim().startsWith(username + ":")) {
                inUserSection = true;
            }
            if (inUserSection && line.trim().startsWith("password:")) {
                return line.trim().substring("password:".length()).trim();
            }
        }
        throw new AssertionError("No password hash found for user: " + username);
    }
}
