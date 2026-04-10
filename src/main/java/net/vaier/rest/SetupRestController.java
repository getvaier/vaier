package net.vaier.rest;

import java.util.List;
import net.vaier.application.CheckSetupStatusUseCase;
import net.vaier.application.CompleteSetupUseCase;
import net.vaier.application.ValidateAwsCredentialsUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/setup")
public class SetupRestController {

    private final CheckSetupStatusUseCase checkSetupStatusUseCase;
    private final ValidateAwsCredentialsUseCase validateAwsCredentialsUseCase;
    private final CompleteSetupUseCase completeSetupUseCase;

    public SetupRestController(
        CheckSetupStatusUseCase checkSetupStatusUseCase,
        ValidateAwsCredentialsUseCase validateAwsCredentialsUseCase,
        CompleteSetupUseCase completeSetupUseCase
    ) {
        this.checkSetupStatusUseCase = checkSetupStatusUseCase;
        this.validateAwsCredentialsUseCase = validateAwsCredentialsUseCase;
        this.completeSetupUseCase = completeSetupUseCase;
    }

    @GetMapping("/status")
    public ResponseEntity<SetupStatusResponse> status() {
        return ResponseEntity.ok(new SetupStatusResponse(checkSetupStatusUseCase.isConfigured()));
    }

    @PostMapping("/validate-aws")
    public ResponseEntity<?> validateAws(@RequestBody ValidateAwsRequest request) {
        try {
            List<String> zones = validateAwsCredentialsUseCase.validateAndListZones(
                request.awsKey(), request.awsSecret()
            );
            return ResponseEntity.ok(new ValidateAwsResponse(zones));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/complete")
    public ResponseEntity<?> complete(@RequestBody CompleteSetupRequest request) {
        try {
            completeSetupUseCase.completeSetup(
                request.domain(), request.awsKey(), request.awsSecret(),
                request.acmeEmail(), request.adminUsername(), request.adminPassword()
            );
            return ResponseEntity.ok(new SetupStatusResponse(true));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    public record SetupStatusResponse(boolean configured) {}
    public record ValidateAwsRequest(String awsKey, String awsSecret) {}
    public record ValidateAwsResponse(List<String> zones) {}
    public record CompleteSetupRequest(String domain, String awsKey, String awsSecret,
                                       String acmeEmail, String adminUsername, String adminPassword) {}
    public record ErrorResponse(String error) {}
}
