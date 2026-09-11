package net.vaier.rest;

import net.vaier.application.GetAppSettingsUseCase;
import net.vaier.application.GetSelfUpdateStatusUseCase;
import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.application.GetAppVersionUseCase;
import net.vaier.application.GetReverseProxyAuditUseCase;
import net.vaier.application.SetSurvivalKitPassphraseUseCase;
import net.vaier.application.TestSmtpCredentialsUseCase;
import net.vaier.application.UpdateAnthropicApiKeyUseCase;
import net.vaier.application.UpdateBackupSettingsUseCase;
import net.vaier.application.UpdateDiskMonitorSettingsUseCase;
import net.vaier.application.UpdateSmtpSettingsUseCase;
import net.vaier.application.UpdateVaierUseCase;
import net.vaier.domain.ReverseProxyAudit;
import net.vaier.domain.ReverseProxyFinding;
import net.vaier.domain.SelfUpdateStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/settings")
public class SettingsRestController {

    private final GetAppSettingsUseCase getAppSettingsUseCase;
    private final GetAppVersionUseCase getAppVersionUseCase;
    private final UpdateSmtpSettingsUseCase updateSmtpSettingsUseCase;
    private final TestSmtpCredentialsUseCase testSmtpCredentialsUseCase;
    private final UpdateDiskMonitorSettingsUseCase updateDiskMonitorSettingsUseCase;
    private final UpdateBackupSettingsUseCase updateBackupSettingsUseCase;
    private final SetSurvivalKitPassphraseUseCase setSurvivalKitPassphraseUseCase;
    private final GetSelfUpdateStatusUseCase getSelfUpdateStatusUseCase;
    private final UpdateVaierUseCase updateVaierUseCase;
    private final UpdateAnthropicApiKeyUseCase updateAnthropicApiKeyUseCase;
    private final GetReverseProxyAuditUseCase getReverseProxyAuditUseCase;

    public SettingsRestController(GetAppSettingsUseCase getAppSettingsUseCase,
                                  GetAppVersionUseCase getAppVersionUseCase,
                                  UpdateSmtpSettingsUseCase updateSmtpSettingsUseCase,
                                  TestSmtpCredentialsUseCase testSmtpCredentialsUseCase,
                                  UpdateDiskMonitorSettingsUseCase updateDiskMonitorSettingsUseCase,
                                  UpdateBackupSettingsUseCase updateBackupSettingsUseCase,
                                  SetSurvivalKitPassphraseUseCase setSurvivalKitPassphraseUseCase,
                                  GetSelfUpdateStatusUseCase getSelfUpdateStatusUseCase,
                                  UpdateVaierUseCase updateVaierUseCase,
                                  UpdateAnthropicApiKeyUseCase updateAnthropicApiKeyUseCase,
                                  GetReverseProxyAuditUseCase getReverseProxyAuditUseCase) {
        this.getAppSettingsUseCase = getAppSettingsUseCase;
        this.getAppVersionUseCase = getAppVersionUseCase;
        this.updateSmtpSettingsUseCase = updateSmtpSettingsUseCase;
        this.testSmtpCredentialsUseCase = testSmtpCredentialsUseCase;
        this.updateDiskMonitorSettingsUseCase = updateDiskMonitorSettingsUseCase;
        this.updateBackupSettingsUseCase = updateBackupSettingsUseCase;
        this.setSurvivalKitPassphraseUseCase = setSurvivalKitPassphraseUseCase;
        this.getSelfUpdateStatusUseCase = getSelfUpdateStatusUseCase;
        this.updateVaierUseCase = updateVaierUseCase;
        this.updateAnthropicApiKeyUseCase = updateAnthropicApiKeyUseCase;
        this.getReverseProxyAuditUseCase = getReverseProxyAuditUseCase;
    }

    @GetMapping("/config")
    public ResponseEntity<AppSettingsResult> getConfig() {
        return ResponseEntity.ok(getAppSettingsUseCase.getSettings());
    }

    /**
     * What Vaier's reverse proxy audit (#354) finds in the Traefik config Vaier writes itself. A read, and only a
     * read: it never moves the notification latch, so opening Settings cannot cost an operator an email.
     *
     * <p>An empty list is the healthy answer and the common one — the surface that draws this paints
     * nothing at all for it.
     */
    @GetMapping("/reverse-proxy-audit")
    public ResponseEntity<ReverseProxyAuditResponse> getReverseProxyAudit() {
        ReverseProxyAudit audit = getReverseProxyAuditUseCase.getReverseProxyAudit();
        return ResponseEntity.ok(new ReverseProxyAuditResponse(audit.summary(),
            audit.findings().stream().map(FindingResponse::of).toList()));
    }

    /** {@code summary} is the domain's own lead sentence, empty when there is nothing wrong. */
    public record ReverseProxyAuditResponse(String summary, List<FindingResponse> findings) {}

    public record FindingResponse(String kind, String entry, String message) {
        static FindingResponse of(ReverseProxyFinding finding) {
            return new FindingResponse(finding.kind().name(), finding.entryName(), finding.message());
        }
    }

    /**
     * The deployed Vaier version, surfaced so the operator always sees which build is running. It doubles as
     * the self-update's liveness probe: the replacement container answering here proves both that it booted
     * and that it is the build we asked for (see {@code SelfUpdateScript}).
     */
    @GetMapping("/version")
    public ResponseEntity<VersionResponse> getVersion() {
        return ResponseEntity.ok(new VersionResponse(getAppVersionUseCase.appVersion()));
    }

    /**
     * Whether a newer Vaier image is being served, and how the last self-update went. Both are reads: the
     * update itself only ever happens because someone pressed the button below.
     */
    @GetMapping("/update")
    public ResponseEntity<UpdateResponse> getUpdate() {
        return ResponseEntity.ok(UpdateResponse.from(
            getSelfUpdateStatusUseCase.updateAvailable(), getSelfUpdateStatusUseCase.lastUpdate()));
    }

    /**
     * Replace Vaier with the newer image. Answers as soon as the host has taken the work — it cannot answer
     * later, because the container serving this request is the one being replaced. The script on the host
     * decides how it ends, and rolls back to the image that was running if the new one does not answer.
     */
    @PostMapping("/update")
    public ResponseEntity<UpdateResponse> update() {
        SelfUpdateStatus started = updateVaierUseCase.updateSelf();
        return started.outcome() == SelfUpdateStatus.Outcome.FAILED
            ? ResponseEntity.unprocessableEntity().body(UpdateResponse.from(false, started))
            : ResponseEntity.accepted().body(UpdateResponse.from(false, started));
    }

    /**
     * What the Settings page is told about updating. {@code outcome} is the <em>last</em> update's, not
     * this request's, so the page can say "the previous one rolled back" — which nothing else would reveal,
     * since a rolled-back Vaier is a running Vaier and looks perfectly healthy.
     *
     * <p>{@code trouble} is the domain's verdict, not the browser's: whether an outcome is worth telling the
     * operator about is a rule, and it lived in two places until the JS copy was deleted.
     */
    record UpdateResponse(boolean available, String outcome, String at, String detail, String runId,
                          boolean trouble) {
        static UpdateResponse from(boolean available, SelfUpdateStatus status) {
            return new UpdateResponse(available, status.outcome().name(), status.at(), status.detail(),
                status.runId(), status.trouble());
        }
    }

    @PutMapping("/smtp")
    public ResponseEntity<?> updateSmtp(@RequestBody UpdateSmtpRequest request) {
        try {
            updateSmtpSettingsUseCase.updateSmtpSettings(
                request.smtpHost(), request.smtpPort(), request.smtpUsername(),
                request.smtpPassword(), request.smtpSender());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST", e.getMessage()));
        }
    }

    @PutMapping("/disk-monitor")
    public ResponseEntity<?> updateDiskMonitor(@RequestBody UpdateDiskMonitorRequest request) {
        try {
            updateDiskMonitorSettingsUseCase.updateDiskMonitorThreshold(request.diskMonitorThresholdPercent());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST", e.getMessage()));
        }
    }

    /**
     * The nightly fleet-backup schedule hour is a plain scheduling preference (like the disk-alert
     * threshold), so it lives here on the settings endpoint rather than on
     * {@code BackupRestController}. {@code GET /settings/config} carries the current value.
     */
    @PutMapping("/backup-schedule")
    public ResponseEntity<?> updateBackupSchedule(@RequestBody UpdateBackupScheduleRequest request) {
        try {
            updateBackupSettingsUseCase.updateBackupScheduleHour(request.backupScheduleHour());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST", e.getMessage()));
        }
    }

    /**
     * Choose the survival kit passphrase. A {@code PUT} of the value alone — it is never read back, so
     * {@code GET /settings/config} answers only {@code hasSurvivalKitPassphrase}.
     */
    @PutMapping("/survival-kit-passphrase")
    public ResponseEntity<?> setSurvivalKitPassphrase(@RequestBody SurvivalKitPassphraseRequest request) {
        try {
            setSurvivalKitPassphraseUseCase.setSurvivalKitPassphrase(request.passphrase());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST", e.getMessage()));
        }
    }

    /**
     * Store the <b>Anthropic API key</b>, or clear it by sending a blank one (#360). A {@code PUT} of the
     * value alone, answered {@code 204}: nothing is ever read back, so {@code GET /settings/config} carries
     * only {@code hasAnthropicApiKey}. Blank is not an error — clearing the field is how the operator turns
     * <b>Chat</b> off again.
     */
    @PutMapping("/anthropic-api-key")
    public ResponseEntity<Void> setAnthropicApiKey(@RequestBody AnthropicApiKeyRequest request) {
        updateAnthropicApiKeyUseCase.updateAnthropicApiKey(request.apiKey());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/smtp/test")
    public ResponseEntity<?> testSmtp(@RequestBody TestSmtpRequest request) {
        try {
            testSmtpCredentialsUseCase.sendTestEmail(request.smtpHost(), request.smtpPort(),
                request.smtpUsername(), request.smtpPassword(),
                request.smtpSender(), request.recipient());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST", e.getMessage()));
        }
    }

    public record VersionResponse(String version) {}
    public record UpdateSmtpRequest(String smtpHost, int smtpPort, String smtpUsername,
                                    String smtpPassword, String smtpSender) {}
    public record TestSmtpRequest(String smtpHost, int smtpPort, String smtpUsername,
                                  String smtpPassword, String smtpSender, String recipient) {}
    public record UpdateDiskMonitorRequest(int diskMonitorThresholdPercent) {}
    public record UpdateBackupScheduleRequest(int backupScheduleHour) {}
    public record SurvivalKitPassphraseRequest(String passphrase) {}
    public record AnthropicApiKeyRequest(String apiKey) {}
}
