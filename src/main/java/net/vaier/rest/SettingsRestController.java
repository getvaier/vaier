package net.vaier.rest;

import net.vaier.application.GetAppSettingsUseCase;
import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.application.GetAppVersionUseCase;
import net.vaier.application.TestSmtpCredentialsUseCase;
import net.vaier.application.UpdateAwsCredentialsUseCase;
import net.vaier.application.UpdateBackupSettingsUseCase;
import net.vaier.application.UpdateDiskMonitorSettingsUseCase;
import net.vaier.application.UpdateSmtpSettingsUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/settings")
public class SettingsRestController {

    private final GetAppSettingsUseCase getAppSettingsUseCase;
    private final GetAppVersionUseCase getAppVersionUseCase;
    private final UpdateAwsCredentialsUseCase updateAwsCredentialsUseCase;
    private final UpdateSmtpSettingsUseCase updateSmtpSettingsUseCase;
    private final TestSmtpCredentialsUseCase testSmtpCredentialsUseCase;
    private final UpdateDiskMonitorSettingsUseCase updateDiskMonitorSettingsUseCase;
    private final UpdateBackupSettingsUseCase updateBackupSettingsUseCase;

    public SettingsRestController(GetAppSettingsUseCase getAppSettingsUseCase,
                                  GetAppVersionUseCase getAppVersionUseCase,
                                  UpdateAwsCredentialsUseCase updateAwsCredentialsUseCase,
                                  UpdateSmtpSettingsUseCase updateSmtpSettingsUseCase,
                                  TestSmtpCredentialsUseCase testSmtpCredentialsUseCase,
                                  UpdateDiskMonitorSettingsUseCase updateDiskMonitorSettingsUseCase,
                                  UpdateBackupSettingsUseCase updateBackupSettingsUseCase) {
        this.getAppSettingsUseCase = getAppSettingsUseCase;
        this.getAppVersionUseCase = getAppVersionUseCase;
        this.updateAwsCredentialsUseCase = updateAwsCredentialsUseCase;
        this.updateSmtpSettingsUseCase = updateSmtpSettingsUseCase;
        this.testSmtpCredentialsUseCase = testSmtpCredentialsUseCase;
        this.updateDiskMonitorSettingsUseCase = updateDiskMonitorSettingsUseCase;
        this.updateBackupSettingsUseCase = updateBackupSettingsUseCase;
    }

    @GetMapping("/config")
    public ResponseEntity<AppSettingsResult> getConfig() {
        return ResponseEntity.ok(getAppSettingsUseCase.getSettings());
    }

    /** The deployed Vaier version, surfaced so the operator always sees which build is running. */
    @GetMapping("/version")
    public ResponseEntity<VersionResponse> getVersion() {
        return ResponseEntity.ok(new VersionResponse(getAppVersionUseCase.appVersion()));
    }

    @PutMapping("/aws")
    public ResponseEntity<?> updateAws(@RequestBody UpdateAwsRequest request) {
        try {
            updateAwsCredentialsUseCase.updateAwsCredentials(request.awsKey(), request.awsSecret());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST", e.getMessage()));
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
     * threshold), so it lives here on the ungated settings endpoint rather than on the enterprise-gated
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
    public record UpdateAwsRequest(String awsKey, String awsSecret) {}
    public record UpdateSmtpRequest(String smtpHost, int smtpPort, String smtpUsername,
                                    String smtpPassword, String smtpSender) {}
    public record TestSmtpRequest(String smtpHost, int smtpPort, String smtpUsername,
                                  String smtpPassword, String smtpSender, String recipient) {}
    public record UpdateDiskMonitorRequest(int diskMonitorThresholdPercent) {}
    public record UpdateBackupScheduleRequest(int backupScheduleHour) {}
}
