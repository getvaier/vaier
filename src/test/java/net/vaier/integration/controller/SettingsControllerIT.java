package net.vaier.integration.controller;

import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.application.ImportConfigurationUseCase.ImportResult;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SettingsControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void export_returnsJsonAsAttachment() throws Exception {
        when(exportConfigurationUseCase.exportConfiguration()).thenReturn("{\"version\":\"1\"}");

        mockMvc.perform(get("/settings/export"))
               .andExpect(status().isOk())
               .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vaier-backup.json\""))
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
               .andExpect(content().string("{\"version\":\"1\"}"));
    }

    @Test
    void export_returns500WhenExportFails() throws Exception {
        when(exportConfigurationUseCase.exportConfiguration())
                .thenThrow(new RuntimeException("file write failed"));

        mockMvc.perform(get("/settings/export"))
               .andExpect(status().isInternalServerError());
    }

    @Test
    void importConfig_returns200WithSuccessResult() throws Exception {
        String json = "{\"version\":\"1\",\"peers\":[],\"services\":[],\"dnsZones\":[],\"users\":[]}";
        ImportResult result = new ImportResult(true, "Import completed", List.of());
        when(importConfigurationUseCase.importConfiguration(json)).thenReturn(result);

        mockMvc.perform(post("/settings/import")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(json))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.success").value(true))
               .andExpect(jsonPath("$.message").value("Import completed"));
    }

    @Test
    void importConfig_returns400WhenImportFails() throws Exception {
        String badJson = "not valid json";
        when(importConfigurationUseCase.importConfiguration(badJson))
                .thenReturn(new ImportResult(false, "Invalid backup file", List.of()));

        mockMvc.perform(post("/settings/import")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(badJson))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.success").value(false))
               .andExpect(jsonPath("$.message").value("Invalid backup file"));
    }

    @Test
    void importConfig_includesWarningsInResponse() throws Exception {
        String json = "{\"version\":\"1\",\"peers\":[],\"services\":[],\"dnsZones\":[],\"users\":[]}";
        ImportResult result = new ImportResult(true, "Import completed with warnings",
                List.of("Skipping duplicate user: alice"));
        when(importConfigurationUseCase.importConfiguration(json)).thenReturn(result);

        mockMvc.perform(post("/settings/import")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(json))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.warnings[0]").value("Skipping duplicate user: alice"));
    }

    @Test
    void getConfig_returnsAppSettings() throws Exception {
        AppSettingsResult settings = new AppSettingsResult(
                "example.com", "****MPLE", "admin@example.com",
                "smtp.example.com", 587, "user@example.com", "noreply@example.com");
        when(getAppSettingsUseCase.getSettings()).thenReturn(settings);

        mockMvc.perform(get("/settings/config"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.domain").value("example.com"))
               .andExpect(jsonPath("$.awsKeyHint").value("****MPLE"))
               .andExpect(jsonPath("$.acmeEmail").value("admin@example.com"))
               .andExpect(jsonPath("$.smtpHost").value("smtp.example.com"))
               .andExpect(jsonPath("$.smtpPort").value(587));
    }

    @Test
    void updateAws_returns200WhenValid() throws Exception {
        mockMvc.perform(put("/settings/aws")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"awsKey":"NEW_KEY","awsSecret":"NEW_SECRET"}
                           """))
               .andExpect(status().isOk());

        verify(updateAwsCredentialsUseCase).updateAwsCredentials("NEW_KEY", "NEW_SECRET");
    }

    @Test
    void updateAws_returns400WhenValidationFails() throws Exception {
        doThrow(new RuntimeException("Invalid credentials"))
                .when(updateAwsCredentialsUseCase).updateAwsCredentials("BAD", "BAD");

        mockMvc.perform(put("/settings/aws")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"awsKey":"BAD","awsSecret":"BAD"}
                           """))
               .andExpect(status().isBadRequest());
    }

    @Test
    void updateSmtp_returns200OnSuccess() throws Exception {
        mockMvc.perform(put("/settings/smtp")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {
                             "smtpHost":"smtp.example.com",
                             "smtpPort":587,
                             "smtpUsername":"user@example.com",
                             "smtpPassword":"pass",
                             "smtpSender":"noreply@example.com"
                           }
                           """))
               .andExpect(status().isOk());

        verify(updateSmtpSettingsUseCase).updateSmtpSettings(
                "smtp.example.com", 587, "user@example.com", "pass", "noreply@example.com");
    }

    @Test
    void updateSmtp_returns400WhenFails() throws Exception {
        doThrow(new RuntimeException("SMTP AUTH failed"))
                .when(updateSmtpSettingsUseCase).updateSmtpSettings(any(), anyInt(), any(), any(), any());

        mockMvc.perform(put("/settings/smtp")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {
                             "smtpHost":"smtp.example.com",
                             "smtpPort":587,
                             "smtpUsername":"user",
                             "smtpPassword":"pass",
                             "smtpSender":"noreply@example.com"
                           }
                           """))
               .andExpect(status().isBadRequest());
    }
}
