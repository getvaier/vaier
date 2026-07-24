package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteHostCredentialUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.SaveHostCredentialUseCase;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.HostCredential;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.SshCredentialDraft;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin CRUD for the one host credential Vaier holds per machine (the credential vault, #307). These
 * paths are non-whitelisted, so they sit under the Tier-3 admin auth chain automatically. A GET only
 * ever returns the redacted {@link HostCredentialView}; secret and passphrase bytes never leave here.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class HostCredentialRestController {

    private final SaveHostCredentialUseCase saveHostCredentialUseCase;
    private final GetHostCredentialUseCase getHostCredentialUseCase;
    private final DeleteHostCredentialUseCase deleteHostCredentialUseCase;

    @PutMapping("/machines/{machine}/ssh-credential")
    public ResponseEntity<CredentialResponse> save(@PathVariable String machine,
                                                   @RequestBody SaveCredentialRequest request) {
        log.info("Saving SSH credential for machine {}", LogSafe.forLog(machine));
        // An invalid authMethod, or a blank username/secret (validated in the domain), throws
        // IllegalArgumentException -> 400 via GlobalExceptionHandler; an unknown machine -> 404.
        SshCredentialDraft draft = new SshCredentialDraft(request.username(),
            AuthMethod.valueOf(request.authMethod()), request.secret(), request.passphrase());
        saveHostCredentialUseCase.saveHostCredential(machine, draft);
        return ResponseEntity.ok(new CredentialResponse(machine, draft.username(),
            draft.authMethod().name(), draft.secret() != null && !draft.secret().isBlank()));
    }

    @GetMapping("/machines/{machine}/ssh-credential")
    public ResponseEntity<CredentialResponse> get(@PathVariable String machine) {
        return getHostCredentialUseCase.getHostCredential(machine)
            .map(view -> ResponseEntity.ok(CredentialResponse.from(machine, view)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/machines/{machine}/ssh-credential")
    public ResponseEntity<Void> delete(@PathVariable String machine) {
        log.info("Deleting SSH credential for machine {}", LogSafe.forLog(machine));
        deleteHostCredentialUseCase.deleteHostCredential(machine);
        return ResponseEntity.noContent().build();
    }

    record SaveCredentialRequest(String username, String authMethod, String secret, String passphrase) {}

    /** The redacted response — reports presence of a secret, never the secret itself. */
    /**
     * The machine is named by the caller's own path segment, not read back off the stored credential:
     * the vault is keyed by identity now, and the browser asked about a name.
     */
    record CredentialResponse(String machineName, String username, String authMethod, boolean hasSecret) {
        static CredentialResponse from(String machineName, HostCredentialView view) {
            return new CredentialResponse(machineName, view.username(),
                view.authMethod().name(), view.hasSecret());
        }
    }
}
