package net.vaier.integration.controller;

import net.vaier.domain.User;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserControllerIT extends VaierWebMvcIntegrationBase {

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
                           {"username":"alice","password":"secret","email":"alice@example.com","displayname":"Alice"}
                           """))
               .andExpect(status().isOk());

        verify(addUserUseCase).addUser("alice", "secret", "alice@example.com", "Alice");
    }

    @Test
    void addUser_returns400WhenUserAlreadyExists() throws Exception {
        doThrow(new RuntimeException("User already exists: alice"))
                .when(addUserUseCase).addUser(eq("alice"), any(), any(), any());

        mockMvc.perform(post("/users")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"username":"alice","password":"secret","email":"alice@example.com","displayname":"Alice"}
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
               .andExpect(jsonPath("$.logoutUrl").value("https://login.example.com/logout"));
    }

    @Test
    void getMe_returnsNullWhenHeaderAbsent() throws Exception {
        when(configResolver.getDomain()).thenReturn("example.com");

        mockMvc.perform(get("/users/me"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.username").doesNotExist());
    }

    @Test
    void getMe_returnsNullLogoutUrlWhenDomainNotConfigured() throws Exception {
        when(configResolver.getDomain()).thenReturn(null);

        mockMvc.perform(get("/users/me").header("Remote-User", "alice"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.logoutUrl").doesNotExist());
    }
}
