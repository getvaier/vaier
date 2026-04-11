package net.vaier.rest;

import net.vaier.application.ExportConfigurationUseCase;
import net.vaier.application.GetAppSettingsUseCase;
import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.application.ImportConfigurationUseCase;
import net.vaier.application.ImportConfigurationUseCase.ImportResult;
import net.vaier.application.UpdateAwsCredentialsUseCase;
import net.vaier.application.UpdateSmtpSettingsUseCase;
import net.vaier.application.service.ImportConfigurationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/settings")
public class SettingsRestController {

    private final ExportConfigurationUseCase exportConfigurationUseCase;
    private final ImportConfigurationUseCase importConfigurationUseCase;
    private final SseEventPublisher sseEventPublisher;
    private final GetAppSettingsUseCase getAppSettingsUseCase;
    private final UpdateAwsCredentialsUseCase updateAwsCredentialsUseCase;
    private final UpdateSmtpSettingsUseCase updateSmtpSettingsUseCase;

    public SettingsRestController(ExportConfigurationUseCase exportConfigurationUseCase,
                                  ImportConfigurationUseCase importConfigurationUseCase,
                                  SseEventPublisher sseEventPublisher,
                                  GetAppSettingsUseCase getAppSettingsUseCase,
                                  UpdateAwsCredentialsUseCase updateAwsCredentialsUseCase,
                                  UpdateSmtpSettingsUseCase updateSmtpSettingsUseCase) {
        this.exportConfigurationUseCase = exportConfigurationUseCase;
        this.importConfigurationUseCase = importConfigurationUseCase;
        this.sseEventPublisher = sseEventPublisher;
        this.getAppSettingsUseCase = getAppSettingsUseCase;
        this.updateAwsCredentialsUseCase = updateAwsCredentialsUseCase;
        this.updateSmtpSettingsUseCase = updateSmtpSettingsUseCase;
    }

    @GetMapping(value = "/import/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter importEvents() {
        return sseEventPublisher.subscribe(ImportConfigurationService.IMPORT_TOPIC);
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> export() {
        try {
            String json = exportConfigurationUseCase.exportConfiguration();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vaier-backup.json\"")
                    .body(json);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportResult> importConfig(@RequestBody String jsonContent) {
        ImportResult result = importConfigurationUseCase.importConfiguration(jsonContent);
        if (!result.success()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/config")
    public ResponseEntity<AppSettingsResult> getConfig() {
        return ResponseEntity.ok(getAppSettingsUseCase.getSettings());
    }

    @PutMapping("/aws")
    public ResponseEntity<?> updateAws(@RequestBody UpdateAwsRequest request) {
        try {
            updateAwsCredentialsUseCase.updateAwsCredentials(request.awsKey(), request.awsSecret());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
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
            return ResponseEntity.internalServerError().body(new ErrorResponse(e.getMessage()));
        }
    }

    public record UpdateAwsRequest(String awsKey, String awsSecret) {}
    public record UpdateSmtpRequest(String smtpHost, int smtpPort, String smtpUsername,
                                    String smtpPassword, String smtpSender) {}
    record ErrorResponse(String error) {}
}
