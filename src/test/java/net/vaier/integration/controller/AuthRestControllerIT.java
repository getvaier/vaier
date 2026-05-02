package net.vaier.integration.controller;

import net.vaier.domain.User;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthRestControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void getUsers_returnsListOfUsers() throws Exception {
        when(getUsersUseCase.getUsers()).thenReturn(List.of(
                new User("alice", "Alice Smith", "alice@example.com", List.of("admins")),
                new User("bob", "Bob Jones", "bob@example.com", List.of())
        ));

        mockMvc.perform(get("/users"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].name").value("alice"))
               .andExpect(jsonPath("$[0].email").value("alice@example.com"))
               .andExpect(jsonPath("$[1].name").value("bob"));
    }

    @Test
    void getUsers_returnsEmptyList() throws Exception {
        when(getUsersUseCase.getUsers()).thenReturn(List.of());

        mockMvc.perform(get("/users"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void addUser_returns200OnSuccess() throws Exception {
        mockMvc.perform(post("/users")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"username":"alice","password":"secret","email":"alice@example.com","displayname":"Alice","groups":["admins"]}
                           """))
               .andExpect(status().isOk());

        verify(addUserUseCase).addUser("alice", "secret", "alice@example.com", "Alice", List.of("admins"));
    }

    @Test
    void addUser_passesGroupsFromRequest() throws Exception {
        mockMvc.perform(post("/users")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"username":"alice","password":"secret","email":"alice@example.com","displayname":"Alice","groups":["family","media"]}
                           """))
               .andExpect(status().isOk());

        verify(addUserUseCase).addUser("alice", "secret", "alice@example.com", "Alice", List.of("family", "media"));
    }

    @Test
    void addUser_returns400WhenUserAlreadyExists() throws Exception {
        doThrow(new RuntimeException("User already exists: alice"))
                .when(addUserUseCase).addUser(eq("alice"), any(), any(), any(), any());

        mockMvc.perform(post("/users")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"username":"alice","password":"secret","email":"alice@example.com","displayname":"Alice","groups":["admins"]}
                           """))
               .andExpect(status().isBadRequest());
    }

    @Test
    void addUser_returns400WhenPasswordMissing() throws Exception {
        doThrow(new IllegalArgumentException("password must not be blank"))
                .when(addUserUseCase).addUser(eq("alice"), isNull(), any(), any(), any());

        mockMvc.perform(post("/users")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"username":"alice","email":"alice@example.com","displayname":"Alice","groups":["admins"]}
                           """))
               .andExpect(status().isBadRequest());
    }

    @Test
    void addUser_returns400WhenUsernameMissing() throws Exception {
        doThrow(new IllegalArgumentException("username must not be blank"))
                .when(addUserUseCase).addUser(isNull(), any(), any(), any(), any());

        mockMvc.perform(post("/users")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"password":"secret","email":"alice@example.com","displayname":"Alice","groups":["admins"]}
                           """))
               .andExpect(status().isBadRequest());
    }

    @Test
    void deleteUser_returns200OnSuccess() throws Exception {
        mockMvc.perform(delete("/users/alice"))
               .andExpect(status().isOk());

        verify(deleteUserUseCase).deleteUser("alice");
    }

    @Test
    void deleteUser_returns400WhenUserNotFound() throws Exception {
        doThrow(new RuntimeException("User not found: alice"))
                .when(deleteUserUseCase).deleteUser("alice");

        mockMvc.perform(delete("/users/alice"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void changePassword_returns200OnSuccess() throws Exception {
        mockMvc.perform(put("/users/alice/password")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"newPassword":"newpass"}
                           """))
               .andExpect(status().isOk());

        verify(changePasswordUseCase).changePassword("alice", "newpass");
    }

    @Test
    void changePassword_returns400WhenUserNotFound() throws Exception {
        doThrow(new RuntimeException("User not found: alice"))
                .when(changePasswordUseCase).changePassword(eq("alice"), any());

        mockMvc.perform(put("/users/alice/password")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"newPassword":"newpass"}
                           """))
               .andExpect(status().isBadRequest());
    }

    @Test
    void getMe_returnsUsernameFromHeader() throws Exception {
        when(configResolver.getDomain()).thenReturn("example.com");

        mockMvc.perform(get("/users/me").header("Remote-User", "alice"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.username").value("alice"))
               .andExpect(jsonPath("$.logoutUrl").value("https://login.example.com/logout?rd=https://vaier.example.com/"))
               .andExpect(jsonPath("$.loginUrl").value("https://login.example.com/?rd=https://vaier.example.com/"));
    }

    @Test
    void getMe_returnsNullWhenHeaderAbsent() throws Exception {
        when(configResolver.getDomain()).thenReturn("example.com");

        mockMvc.perform(get("/users/me"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.username").doesNotExist())
               .andExpect(jsonPath("$.loginUrl").value("https://login.example.com/?rd=https://vaier.example.com/"));
    }

    @Test
    void getMe_returnsNullLogoutUrlWhenDomainNotConfigured() throws Exception {
        when(configResolver.getDomain()).thenReturn(null);

        mockMvc.perform(get("/users/me").header("Remote-User", "alice"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.logoutUrl").doesNotExist())
               .andExpect(jsonPath("$.loginUrl").doesNotExist());
    }

    @Test
    void getMe_returnsDisplaynameAndEmailFromHeaders() throws Exception {
        when(configResolver.getDomain()).thenReturn("example.com");

        mockMvc.perform(get("/users/me")
                       .header("Remote-User", "alice")
                       .header("Remote-Name", "Alice Example")
                       .header("Remote-Email", "alice@example.com"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.username").value("alice"))
               .andExpect(jsonPath("$.displayname").value("Alice Example"))
               .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    @Test
    void updateEmail_returns200OnSuccess() throws Exception {
        mockMvc.perform(put("/users/alice/email")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"email":"new@example.com"}
                           """))
               .andExpect(status().isOk());

        verify(updateUserEmailUseCase).updateEmail("alice", "new@example.com");
    }

    @Test
    void updateEmail_returns400WhenInvalid() throws Exception {
        doThrow(new IllegalArgumentException("email is not a valid format"))
                .when(updateUserEmailUseCase).updateEmail(eq("alice"), any());

        mockMvc.perform(put("/users/alice/email")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"email":"bogus"}
                           """))
               .andExpect(status().isBadRequest());
    }

    @Test
    void updateDisplayName_returns200OnSuccess() throws Exception {
        mockMvc.perform(put("/users/alice/displayname")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"displayname":"Alice Example"}
                           """))
               .andExpect(status().isOk());

        verify(updateUserDisplayNameUseCase).updateDisplayName("alice", "Alice Example");
    }

    @Test
    void updateDisplayName_returns400WhenInvalid() throws Exception {
        doThrow(new IllegalArgumentException("displayname must not be blank"))
                .when(updateUserDisplayNameUseCase).updateDisplayName(eq("alice"), any());

        mockMvc.perform(put("/users/alice/displayname")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"displayname":""}
                           """))
               .andExpect(status().isBadRequest());
    }

    // --- groups endpoints ---

    @Test
    void getGroups_returnsListOfGroups() throws Exception {
        when(getGroupsUseCase.getGroups()).thenReturn(List.of("admins", "family", "media"));

        mockMvc.perform(get("/groups"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0]").value("admins"))
               .andExpect(jsonPath("$[1]").value("family"))
               .andExpect(jsonPath("$[2]").value("media"));
    }

    @Test
    void getGroups_returnsEmptyList() throws Exception {
        when(getGroupsUseCase.getGroups()).thenReturn(List.of());

        mockMvc.perform(get("/groups"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void updateUserGroups_returns200OnSuccess() throws Exception {
        mockMvc.perform(put("/users/alice/groups")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"groups":["admins","family"]}
                           """))
               .andExpect(status().isOk());

        verify(updateUserGroupsUseCase).updateUserGroups("alice", List.of("admins", "family"));
    }

    @Test
    void updateUserGroups_returns400WhenUserNotFound() throws Exception {
        doThrow(new RuntimeException("User not found: alice"))
                .when(updateUserGroupsUseCase).updateUserGroups(eq("alice"), any());

        mockMvc.perform(put("/users/alice/groups")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"groups":["admins"]}
                           """))
               .andExpect(status().isBadRequest());
    }

    @Test
    void deleteGroup_returns200OnSuccess() throws Exception {
        mockMvc.perform(delete("/groups/family"))
               .andExpect(status().isOk());

        verify(deleteGroupUseCase).deleteGroup("family");
    }

    @Test
    void deleteGroup_returns400WhenInvalid() throws Exception {
        doThrow(new IllegalArgumentException("group name must not be blank"))
                .when(deleteGroupUseCase).deleteGroup("");

        mockMvc.perform(delete("/groups/ "))
               .andExpect(status().isBadRequest());
    }
}
