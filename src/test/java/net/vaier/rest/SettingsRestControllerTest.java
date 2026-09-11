package net.vaier.rest;

import net.vaier.application.GetAppSettingsUseCase;
import net.vaier.application.GetReverseProxyAuditUseCase;
import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.application.GetAppVersionUseCase;
import net.vaier.application.GetSelfUpdateStatusUseCase;
import net.vaier.application.SetSurvivalKitPassphraseUseCase;
import net.vaier.application.TestSmtpCredentialsUseCase;
import net.vaier.application.UpdateAnthropicApiKeyUseCase;
import net.vaier.application.UpdateBackupSettingsUseCase;
import net.vaier.application.UpdateDiskMonitorSettingsUseCase;
import net.vaier.application.UpdateSmtpSettingsUseCase;
import net.vaier.domain.ReverseProxyAudit;
import net.vaier.domain.ReverseProxyConfig;
import net.vaier.domain.SelfUpdateStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingsRestControllerTest {

    @Mock GetAppSettingsUseCase getAppSettingsUseCase;
    @Mock GetAppVersionUseCase getAppVersionUseCase;
    @Mock UpdateSmtpSettingsUseCase updateSmtpSettingsUseCase;
    @Mock TestSmtpCredentialsUseCase testSmtpCredentialsUseCase;
    @Mock UpdateDiskMonitorSettingsUseCase updateDiskMonitorSettingsUseCase;
    @Mock UpdateBackupSettingsUseCase updateBackupSettingsUseCase;
    @Mock SetSurvivalKitPassphraseUseCase setSurvivalKitPassphraseUseCase;
    @Mock UpdateAnthropicApiKeyUseCase updateAnthropicApiKeyUseCase;
    @Mock GetSelfUpdateStatusUseCase getSelfUpdateStatusUseCase;
    @Mock GetReverseProxyAuditUseCase getReverseProxyAuditUseCase;

    @InjectMocks
    SettingsRestController controller;

    @Test
    void getVersion_returnsTheRunningBuildVersion() {
        when(getAppVersionUseCase.appVersion()).thenReturn("1.0.0");

        ResponseEntity<SettingsRestController.VersionResponse> response = controller.getVersion();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().version()).isEqualTo("1.0.0");
    }

    @Test
    void getConfig_returnsCurrentSettings() {
        AppSettingsResult settings = new AppSettingsResult("example.com", "admin@example.com",
                "smtp.example.com", 587, "user@example.com", "noreply@example.com",
                "COVERED", "Covered", "OK",
                "Wildcard DNS is working — *.example.com resolves to 52.29.74.114.",
                85, false, 2, "Europe/Oslo", true, true);
        when(getAppSettingsUseCase.getSettings()).thenReturn(settings);

        ResponseEntity<AppSettingsResult> response = controller.getConfig();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(settings);
        assertThat(response.getBody().backupScheduleHour()).isEqualTo(2);
    }

    /**
     * The passphrase goes in and is never handed back: {@code GET /settings/config} says only whether one
     * exists. A settings page that could show it would put every backup passphrase in the fleet behind one
     * shoulder-surf, and the browser has no use for the value it does not already have.
     */
    @Test
    void setSurvivalKitPassphrase_storesItAndReturns200() {
        ResponseEntity<?> response = controller.setSurvivalKitPassphrase(
                new SettingsRestController.SurvivalKitPassphraseRequest("correct horse battery staple"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(setSurvivalKitPassphraseUseCase).setSurvivalKitPassphrase("correct horse battery staple");
    }

    @Test
    void setSurvivalKitPassphrase_returns400WhenTheUseCaseRefusesIt() {
        doThrow(new IllegalArgumentException("The survival kit passphrase must not be blank"))
                .when(setSurvivalKitPassphraseUseCase).setSurvivalKitPassphrase("  ");

        ResponseEntity<?> response = controller.setSurvivalKitPassphrase(
                new SettingsRestController.SurvivalKitPassphraseRequest("  "));

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
    void updateDiskMonitor_returns200AndDelegates() {
        ResponseEntity<?> response = controller.updateDiskMonitor(
                new SettingsRestController.UpdateDiskMonitorRequest(70));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(updateDiskMonitorSettingsUseCase).updateDiskMonitorThreshold(70);
    }

    @Test
    void updateDiskMonitor_returns400OnInvalidValue() {
        doThrow(new IllegalArgumentException("out of range"))
                .when(updateDiskMonitorSettingsUseCase).updateDiskMonitorThreshold(org.mockito.ArgumentMatchers.anyInt());

        ResponseEntity<?> response = controller.updateDiskMonitor(
                new SettingsRestController.UpdateDiskMonitorRequest(0));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void updateBackupSchedule_returns200AndDelegates() {
        ResponseEntity<?> response = controller.updateBackupSchedule(
                new SettingsRestController.UpdateBackupScheduleRequest(5));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(updateBackupSettingsUseCase).updateBackupScheduleHour(5);
    }

    @Test
    void updateBackupSchedule_returns400OnInvalidValue() {
        doThrow(new IllegalArgumentException("out of range"))
                .when(updateBackupSettingsUseCase).updateBackupScheduleHour(org.mockito.ArgumentMatchers.anyInt());

        ResponseEntity<?> response = controller.updateBackupSchedule(
                new SettingsRestController.UpdateBackupScheduleRequest(24));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
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

    @Test
    void getUpdate_carriesTheDomainsOwnVerdictOnWhetherTheLastUpdateIsTrouble() {
        // The browser used to re-derive this from the raw outcome name. One rule, decided once: a rollback is
        // trouble precisely BECAUSE Vaier is running again, which no reader of the enum would guess.
        when(getSelfUpdateStatusUseCase.updateAvailable()).thenReturn(false);
        when(getSelfUpdateStatusUseCase.lastUpdate()).thenReturn(
            new SelfUpdateStatus("run-1", SelfUpdateStatus.Outcome.ROLLED_BACK, "2026-08-04T06:00:00Z", "img"));

        ResponseEntity<SettingsRestController.UpdateResponse> response = controller.getUpdate();

        assertThat(response.getBody().trouble()).isTrue();
    }

    @Test
    void getUpdate_asuccessfulUpdateIsNotTrouble() {
        when(getSelfUpdateStatusUseCase.updateAvailable()).thenReturn(false);
        when(getSelfUpdateStatusUseCase.lastUpdate()).thenReturn(
            new SelfUpdateStatus("run-1", SelfUpdateStatus.Outcome.UPGRADED, "2026-08-04T06:00:00Z", "img"));

        assertThat(controller.getUpdate().getBody().trouble()).isFalse();
    }

    // --- the Anthropic API key (#360) ------------------------------------------------------------------

    /**
     * A {@code PUT} of the value alone, answered {@code 204}: the key is never read back, so
     * {@code GET /settings/config} answers only {@code hasAnthropicApiKey}.
     */
    @Test
    void setAnthropicApiKey_storesItAndReturns204() {
        ResponseEntity<?> response = controller.setAnthropicApiKey(
                new SettingsRestController.AnthropicApiKeyRequest("sk-ant-api03-the-key"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(updateAnthropicApiKeyUseCase).updateAnthropicApiKey("sk-ant-api03-the-key");
    }

    /** Blank clears it — that is how the operator turns Ask off again, not an error. */
    @Test
    void setAnthropicApiKey_blankClearsTheStoredKey() {
        ResponseEntity<?> response = controller.setAnthropicApiKey(
                new SettingsRestController.AnthropicApiKeyRequest("  "));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(updateAnthropicApiKeyUseCase).updateAnthropicApiKey("  ");
    }

    // --- the reverse proxy audit (#354): read-only, and silent when there is nothing wrong ---

    @Test
    void getReverseProxyAudit_saysNothingIsWrongWithACleanConfig() {
        when(getReverseProxyAuditUseCase.getReverseProxyAudit())
                .thenReturn(ReverseProxyAudit.of(ReverseProxyConfig.empty()));

        ResponseEntity<SettingsRestController.ReverseProxyAuditResponse> response =
                controller.getReverseProxyAudit();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().findings()).isEmpty();
        assertThat(response.getBody().summary()).isEmpty();
    }

    @Test
    void getReverseProxyAudit_namesEachBrokenEntry() {
        when(getReverseProxyAuditUseCase.getReverseProxyAudit())
                .thenReturn(ReverseProxyAudit.of(ReverseProxyConfig.builder()
                        .middlewares(List.of(ReverseProxyConfig.ConfiguredMiddleware.builder()
                                .protocol(ReverseProxyConfig.Protocol.HTTP)
                                .name("orphaned-redirect").build()))
                        .build()));

        ResponseEntity<SettingsRestController.ReverseProxyAuditResponse> response =
                controller.getReverseProxyAudit();

        assertThat(response.getBody().findings()).hasSize(1);
        assertThat(response.getBody().findings().get(0).entry()).isEqualTo("orphaned-redirect");
        assertThat(response.getBody().findings().get(0).kind()).isEqualTo("UNREFERENCED_MIDDLEWARE");
        assertThat(response.getBody().findings().get(0).message()).contains("no router");
        // The lead sentence is the domain's, carried through rather than re-phrased at the edge.
        assertThat(response.getBody().summary()).contains("Vaier changed nothing");
    }
}
