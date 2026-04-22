package net.vaier.rest;

import net.vaier.application.GetAppSettingsUseCase;
import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.application.TestSmtpCredentialsUseCase;
import net.vaier.application.UpdateAwsCredentialsUseCase;
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
    private final UpdateAwsCredentialsUseCase updateAwsCredentialsUseCase;
    private final UpdateSmtpSettingsUseCase updateSmtpSettingsUseCase;
    private final TestSmtpCredentialsUseCase testSmtpCredentialsUseCase;

    public SettingsRestController(GetAppSettingsUseCase getAppSettingsUseCase,
                                  UpdateAwsCredentialsUseCase updateAwsCredentialsUseCase,
                                  UpdateSmtpSettingsUseCase updateSmtpSettingsUseCase,
                                  TestSmtpCredentialsUseCase testSmtpCredentialsUseCase) {
        this.getAppSettingsUseCase = getAppSettingsUseCase;
        this.updateAwsCredentialsUseCase = updateAwsCredentialsUseCase;
        this.updateSmtpSettingsUseCase = updateSmtpSettingsUseCase;
        this.testSmtpCredentialsUseCase = testSmtpCredentialsUseCase;
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
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
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
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    public record UpdateAwsRequest(String awsKey, String awsSecret) {}
    public record UpdateSmtpRequest(String smtpHost, int smtpPort, String smtpUsername,
                                    String smtpPassword, String smtpSender) {}
    public record TestSmtpRequest(String smtpHost, int smtpPort, String smtpUsername,
                                  String smtpPassword, String smtpSender, String recipient) {}
    record ErrorResponse(String error) {}
}
