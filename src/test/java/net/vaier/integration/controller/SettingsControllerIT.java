package net.vaier.integration.controller;

import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SettingsControllerIT extends VaierWebMvcIntegrationBase {

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
