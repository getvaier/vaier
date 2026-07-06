package net.vaier.application.service;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.HostCredential;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.port.ForPersistingHostCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TerminalServiceTest {

    @Mock ForPersistingHostCredentials forPersistingHostCredentials;

    @InjectMocks TerminalService service;

    @Test
    void saveHostCredential_persistsViaPort() {
        HostCredential credential = new HostCredential("nas", "admin", AuthMethod.PASSWORD, "s3cret", null, false);

        service.saveHostCredential(credential);

        verify(forPersistingHostCredentials).save(credential);
    }

    @Test
    void getHostCredential_returnsRedactedView_neverSecretBytes() {
        when(forPersistingHostCredentials.getByMachine("nas")).thenReturn(Optional.of(
            new HostCredential("nas", "admin", AuthMethod.PRIVATE_KEY, "-----BEGIN KEY-----", "keypass", false)));

        Optional<HostCredentialView> view = service.getHostCredential("nas");

        assertThat(view).contains(new HostCredentialView("nas", "admin", AuthMethod.PRIVATE_KEY, true));
    }

    @Test
    void getHostCredential_unknownMachine_returnsEmpty() {
        when(forPersistingHostCredentials.getByMachine("ghost")).thenReturn(Optional.empty());

        assertThat(service.getHostCredential("ghost")).isEmpty();
    }

    @Test
    void deleteHostCredential_deletesViaPort() {
        service.deleteHostCredential("nas");

        verify(forPersistingHostCredentials).deleteByMachine("nas");
    }
}
