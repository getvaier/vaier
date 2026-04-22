package net.vaier.rest;

import net.vaier.application.ExportConfigurationUseCase;
import net.vaier.application.GetAppSettingsUseCase;
import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.application.ImportConfigurationUseCase;
import net.vaier.application.ImportConfigurationUseCase.ImportResult;
import net.vaier.application.TestSmtpCredentialsUseCase;
import net.vaier.application.UpdateAwsCredentialsUseCase;
import net.vaier.application.UpdateSmtpSettingsUseCase;
import net.vaier.adapter.driven.SseEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingsRestControllerTest {

    @Mock ExportConfigurationUseCase exportConfigurationUseCase;
    @Mock ImportConfigurationUseCase importConfigurationUseCase;
    @Mock SseEventPublisher sseEventPublisher;
    @Mock GetAppSettingsUseCase getAppSettingsUseCase;
    @Mock UpdateAwsCredentialsUseCase updateAwsCredentialsUseCase;
    @Mock UpdateSmtpSettingsUseCase updateSmtpSettingsUseCase;
    @Mock TestSmtpCredentialsUseCase testSmtpCredentialsUseCase;

    @InjectMocks
    SettingsRestController controller;

    @Test
    void export_delegatesToUseCaseAndReturnsJson() {
        when(exportConfigurationUseCase.exportConfiguration()).thenReturn("{\"version\":\"1\"}");

        ResponseEntity<String> response = controller.export();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("{\"version\":\"1\"}");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("vaier-backup.json");
    }

    @Test
    void export_returns500WhenUseCaseFails() {
        when(exportConfigurationUseCase.exportConfiguration())
                .thenThrow(new RuntimeException("export failed"));

        ResponseEntity<String> response = controller.export();

        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void importConfig_delegatesToUseCaseAndReturnsResult() {
        String json = "{\"version\":\"1\"}";
        ImportResult importResult = new ImportResult(true, "Import completed", List.of());
        when(importConfigurationUseCase.importConfiguration(json)).thenReturn(importResult);

        ResponseEntity<ImportResult> response = controller.importConfig(json);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(importResult);
        verify(importConfigurationUseCase).importConfiguration(json);
    }

    @Test
    void importConfig_returns400WhenImportFails() {
        String json = "not json";
        when(importConfigurationUseCase.importConfiguration(json))
                .thenReturn(new ImportResult(false, "Invalid backup file", List.of()));

        ResponseEntity<ImportResult> response = controller.importConfig(json);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void getConfig_returnsCurrentSettings() {
        AppSettingsResult settings = new AppSettingsResult("example.com", "****MPLE", "admin@example.com",
                "smtp.example.com", 587, "user@example.com", "noreply@example.com");
        when(getAppSettingsUseCase.getSettings()).thenReturn(settings);

        ResponseEntity<AppSettingsResult> response = controller.getConfig();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(settings);
    }

    @Test
    void updateAws_returns200WhenValid() {
        ResponseEntity<?> response = controller.updateAws(
                new SettingsRestController.UpdateAwsRequest("NEW_KEY", "NEW_SECRET"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(updateAwsCredentialsUseCase).updateAwsCredentials("NEW_KEY", "NEW_SECRET");
    }

    @Test
    void updateAws_returns400WhenValidationFails() {
        doThrow(new RuntimeException("Invalid credentials"))
                .when(updateAwsCredentialsUseCase).updateAwsCredentials("BAD", "BAD");

        ResponseEntity<?> response = controller.updateAws(
                new SettingsRestController.UpdateAwsRequest("BAD", "BAD"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void updateSmtp_returns200OnSuccess() {
        ResponseEntity<?> response = controller.updateSmtp(
                new SettingsRestController.UpdateSmtpRequest("smtp.example.com", 587,
                        "user@example.com", "pass", "noreply@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(updateSmtpSettingsUseCase).updateSmtpSettings(
                "smtp.example.com", 587, "user@example.com", "pass", "noreply@example.com");
    }

    @Test
    void updateSmtp_returns400WhenFails() {
        doThrow(new RuntimeException("SMTP AUTH failed"))
                .when(updateSmtpSettingsUseCase).updateSmtpSettings(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        ResponseEntity<?> response = controller.updateSmtp(
                new SettingsRestController.UpdateSmtpRequest("smtp.example.com", 587,
                        "user@example.com", "pass", "noreply@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void testSmtp_returns200WhenTestEmailSends() {
        ResponseEntity<?> response = controller.testSmtp(
                new SettingsRestController.TestSmtpRequest("smtp.example.com", 587,
                        "user@example.com", "pass", "noreply@example.com", "admin@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(testSmtpCredentialsUseCase).sendTestEmail(
                "smtp.example.com", 587, "user@example.com", "pass",
                "noreply@example.com", "admin@example.com");
    }

    @Test
    void testSmtp_returns400WhenSendFails() {
        doThrow(new RuntimeException("SMTP AUTH failed: 534"))
                .when(testSmtpCredentialsUseCase).sendTestEmail(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        ResponseEntity<?> response = controller.testSmtp(
                new SettingsRestController.TestSmtpRequest("smtp.example.com", 587,
                        "user@example.com", "badpass", "noreply@example.com", "admin@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
