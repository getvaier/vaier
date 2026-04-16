package net.vaier.integration.controller;

import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SetupControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void status_returnsNotConfigured() throws Exception {
        when(checkSetupStatusUseCase.isConfigured()).thenReturn(false);

        mockMvc.perform(get("/api/setup/status"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void status_returnsConfigured() throws Exception {
        when(checkSetupStatusUseCase.isConfigured()).thenReturn(true);

        mockMvc.perform(get("/api/setup/status"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    void validateAws_returns200WithZones() throws Exception {
        when(validateAwsCredentialsUseCase.validateAndListZones("key", "secret"))
                .thenReturn(List.of("vaier.net", "example.com"));

        mockMvc.perform(post("/api/setup/validate-aws")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"awsKey":"key","awsSecret":"secret"}
                           """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.zones[0]").value("vaier.net"))
               .andExpect(jsonPath("$.zones[1]").value("example.com"));
    }

    @Test
    void validateAws_returns400WhenCredentialsInvalid() throws Exception {
        when(validateAwsCredentialsUseCase.validateAndListZones("bad", "creds"))
                .thenThrow(new RuntimeException("Invalid credentials"));

        mockMvc.perform(post("/api/setup/validate-aws")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"awsKey":"bad","awsSecret":"creds"}
                           """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error").value("Invalid credentials"));
    }

    @Test
    void complete_returns200OnSuccess() throws Exception {
        mockMvc.perform(post("/api/setup/complete")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {
                             "domain":"example.com",
                             "awsKey":"key",
                             "awsSecret":"secret",
                             "acmeEmail":"admin@example.com",
                             "adminUsername":"admin",
                             "adminPassword":"password"
                           }
                           """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.configured").value(true));

        verify(completeSetupUseCase).completeSetup(
                "example.com", "key", "secret", "admin@example.com", "admin", "password");
    }

    @Test
    void complete_returns409WhenAlreadyConfigured() throws Exception {
        doThrow(new IllegalStateException("Setup has already been completed"))
                .when(completeSetupUseCase).completeSetup(any(), any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/setup/complete")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {
                             "domain":"example.com",
                             "awsKey":"key",
                             "awsSecret":"secret",
                             "acmeEmail":"admin@example.com",
                             "adminUsername":"admin",
                             "adminPassword":"password"
                           }
                           """))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.error").value("Setup has already been completed"));
    }

    @Test
    void complete_returns400WhenSetupFails() throws Exception {
        doThrow(new RuntimeException("Config write failed"))
                .when(completeSetupUseCase).completeSetup(any(), any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/setup/complete")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {
                             "domain":"example.com",
                             "awsKey":"key",
                             "awsSecret":"secret",
                             "acmeEmail":"admin@example.com",
                             "adminUsername":"admin",
                             "adminPassword":"password"
                           }
                           """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error").value("Config write failed"));
    }

    @Test
    void nonSetupPath_redirectsToSetupHtml_whenNotConfigured() throws Exception {
        when(setupStateHolder.isConfigured()).thenReturn(false);

        mockMvc.perform(get("/users"))
               .andExpect(status().is3xxRedirection())
               .andExpect(header().string("Location", "/setup.html"));
    }

    @Test
    void cssFile_allowedEvenWhenNotConfigured() throws Exception {
        when(setupStateHolder.isConfigured()).thenReturn(false);

        // Static resources ending in .css must pass through the filter
        mockMvc.perform(get("/app.css"))
               .andExpect(result -> {
                   int status = result.getResponse().getStatus();
                   // 404 (no such static file in test) is fine — what matters is NOT a redirect (302)
                   assert status != 302 : "CSS request should not be redirected to /setup.html";
               });
    }
}
