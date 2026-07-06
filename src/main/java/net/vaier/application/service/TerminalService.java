package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteHostCredentialUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.SaveHostCredentialUseCase;
import net.vaier.domain.HostCredential;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.port.ForPersistingHostCredentials;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The remote-shell / credential-vault domain service. Slice 1 covers the credential vault only: it
 * stores, reads (redacted) and deletes the one host credential Vaier holds per machine. Reads go
 * through the domain's {@link HostCredential#toView() redaction} so raw secrets never leave the
 * process. Later slices add the SSH session itself on top of this same service.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TerminalService implements
    SaveHostCredentialUseCase,
    GetHostCredentialUseCase,
    DeleteHostCredentialUseCase {

    private final ForPersistingHostCredentials forPersistingHostCredentials;

    @Override
    public void saveHostCredential(HostCredential credential) {
        forPersistingHostCredentials.save(credential);
    }

    @Override
    public Optional<HostCredentialView> getHostCredential(String machineName) {
        return forPersistingHostCredentials.getByMachine(machineName).map(HostCredential::toView);
    }

    @Override
    public void deleteHostCredential(String machineName) {
        forPersistingHostCredentials.deleteByMachine(machineName);
    }
}
