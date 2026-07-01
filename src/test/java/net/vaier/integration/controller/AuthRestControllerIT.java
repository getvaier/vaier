package net.vaier.integration.controller;

import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthRestControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void removedUserManagementEndpoints_areGone() throws Exception {
        // Name and email are now provider-owned (Google via oauth2-proxy); the legacy
        // Authelia user-management surface has been removed. Only /users/me survives.
        mockMvc.perform(get("/users")).andExpect(status().isNotFound());
        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().isNotFound());
        mockMvc.perform(delete("/users/alice")).andExpect(status().isNotFound());
        mockMvc.perform(put("/users/alice/email").contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().isNotFound());
        mockMvc.perform(put("/users/alice/displayname").contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().isNotFound());
        mockMvc.perform(put("/users/alice/groups").contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().isNotFound());
        mockMvc.perform(get("/groups")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/groups/family")).andExpect(status().isNotFound());
    }

    @Test
    void getMe_returnsUsernameFromHeader() throws Exception {
        when(configResolver.getDomain()).thenReturn("example.com");
        when(configResolver.getConsoleAuthMode()).thenReturn(net.vaier.domain.AuthMode.AUTHELIA);

        mockMvc.perform(get("/users/me").header("Remote-User", "alice"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.username").value("alice"))
               .andExpect(jsonPath("$.logoutUrl").value("https://login.example.com/logout?rd=https://vaier.example.com/"))
               .andExpect(jsonPath("$.loginUrl").value("https://login.example.com/?rd=https://vaier.example.com/"));
    }

    @Test
    void getMe_returnsNullWhenHeaderAbsent() throws Exception {
        when(configResolver.getDomain()).thenReturn("example.com");
        when(configResolver.getConsoleAuthMode()).thenReturn(net.vaier.domain.AuthMode.AUTHELIA);

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
        when(configResolver.getConsoleAuthMode()).thenReturn(net.vaier.domain.AuthMode.AUTHELIA);

        mockMvc.perform(get("/users/me")
                       .header("Remote-User", "alice")
                       .header("Remote-Name", "Alice Example")
                       .header("Remote-Email", "alice@example.com"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.username").value("alice"))
               .andExpect(jsonPath("$.displayname").value("Alice Example"))
               .andExpect(jsonPath("$.email").value("alice@example.com"));
    }
}
