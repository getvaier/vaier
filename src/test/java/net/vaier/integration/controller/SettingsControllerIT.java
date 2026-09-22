package net.vaier.integration.controller;

import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;

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
                "example.com", "admin@example.com",
                "smtp.example.com", 587, "user@example.com", "noreply@example.com",
                "NOT_RESOLVING", "Not resolving", "ERROR",
                "Wildcard DNS is not set up. Create one record — *.example.com A 52.29.74.114 — "
                        + "and every service Vaier publishes will resolve.",
                85, false, 2, "Europe/Oslo", false, false, false, true);
        when(getAppSettingsUseCase.getSettings()).thenReturn(settings);

        mockMvc.perform(get("/settings/config"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.domain").value("example.com"))
               .andExpect(jsonPath("$.acmeEmail").value("admin@example.com"))
               .andExpect(jsonPath("$.smtpHost").value("smtp.example.com"))
               .andExpect(jsonPath("$.smtpPort").value(587))
               .andExpect(jsonPath("$.wildcardDnsStatus").value("NOT_RESOLVING"))
               .andExpect(jsonPath("$.wildcardDnsLabel").value("Not resolving"))
               .andExpect(jsonPath("$.wildcardDnsSeverity").value("ERROR"))
               .andExpect(jsonPath("$.wildcardDnsMessage").value(
                       containsString("*.example.com")))
               .andExpect(jsonPath("$.diskMonitorThresholdPercent").value(85))
               .andExpect(jsonPath("$.backupScheduleHour").value(2));
    }

    @Test
    void updateDiskMonitor_returns200AndDelegates() throws Exception {
        mockMvc.perform(put("/settings/disk-monitor")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"diskMonitorThresholdPercent":70}
                           """))
               .andExpect(status().isOk());

        verify(updateDiskMonitorSettingsUseCase).updateDiskMonitorThreshold(70);
    }

    @Test
    void updateDiskMonitor_returns400OnInvalidValue() throws Exception {
        doThrow(new IllegalArgumentException("out of range"))
                .when(updateDiskMonitorSettingsUseCase).updateDiskMonitorThreshold(anyInt());

        mockMvc.perform(put("/settings/disk-monitor")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"diskMonitorThresholdPercent":0}
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
               .andExpect(status().isBadRequest())
               // Settings keeps its deliberate Exception->400 mapping (bad SMTP credentials are a
               // client error), but still emits the same ApiError envelope as everything else.
               .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
               .andExpect(jsonPath("$.message").value("SMTP AUTH failed"));
    }
}
