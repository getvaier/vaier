package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import net.vaier.application.ClearSharedServiceCredentialUseCase;
import net.vaier.application.GetServiceCredentialsUseCase;
import net.vaier.application.RemovePersonalServiceCredentialUseCase;
import net.vaier.application.SetPersonalServiceCredentialUseCase;
import net.vaier.application.SetSharedServiceCredentialUseCase;
import net.vaier.domain.ServiceCredentials;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin management of <b>service credentials</b>: the login Vaier hands a social-gated published service
 * for someone it let in. Behind the console's own auth like every other {@code /access} path. Passwords are
 * write-only — no response here ever carries one.
 */
@RestController
@RequiredArgsConstructor
public class ServiceCredentialRestController {

    private final GetServiceCredentialsUseCase getServiceCredentialsUseCase;
    private final SetSharedServiceCredentialUseCase setSharedServiceCredentialUseCase;
    private final ClearSharedServiceCredentialUseCase clearSharedServiceCredentialUseCase;
    private final SetPersonalServiceCredentialUseCase setPersonalServiceCredentialUseCase;
    private final RemovePersonalServiceCredentialUseCase removePersonalServiceCredentialUseCase;

    @GetMapping("/access/services/credentials")
    public Map<String, ServiceCredentialsResponse> list() {
        Map<String, ServiceCredentialsResponse> byService = new LinkedHashMap<>();
        getServiceCredentialsUseCase.getServiceCredentials().getByService()
            .forEach((host, entry) -> byService.put(host, ServiceCredentialsResponse.from(entry)));
        return byService;
    }

    @PutMapping("/access/services/{host}/credentials/shared")
    public ResponseEntity<Void> setShared(@PathVariable String host, @RequestBody CredentialRequest request) {
        setSharedServiceCredentialUseCase.setSharedServiceCredential(host, request.username(), request.password());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/access/services/{host}/credentials/shared")
    public ResponseEntity<Void> clearShared(@PathVariable String host) {
        clearSharedServiceCredentialUseCase.clearSharedServiceCredential(host);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/access/services/{host}/credentials/people/{email}")
    public ResponseEntity<Void> setPersonal(@PathVariable String host, @PathVariable String email,
                                            @RequestBody CredentialRequest request) {
        setPersonalServiceCredentialUseCase.setPersonalServiceCredential(host, email, request.username(),
            request.password());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/access/services/{host}/credentials/people/{email}")
    public ResponseEntity<Void> removePersonal(@PathVariable String host, @PathVariable String email) {
        removePersonalServiceCredentialUseCase.removePersonalServiceCredential(host, email);
        return ResponseEntity.noContent().build();
    }

    public record CredentialRequest(String username, String password) {}

    public record PersonResponse(String email, String username) {}

    /** Who the service sees, never with what password. */
    public record ServiceCredentialsResponse(String sharedUsername, List<PersonResponse> people) {
        static ServiceCredentialsResponse from(ServiceCredentials.Entry entry) {
            return new ServiceCredentialsResponse(
                entry.shared() == null ? null : entry.shared().getUsername(),
                entry.personal().entrySet().stream()
                    .map(p -> new PersonResponse(p.getKey(), p.getValue().getUsername()))
                    .toList());
        }
    }
}
