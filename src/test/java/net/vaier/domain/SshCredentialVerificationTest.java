package net.vaier.domain;

import net.vaier.domain.port.ForVerifyingSshCredentials;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SshCredentialVerificationTest {

    private final ForVerifyingSshCredentials prober = mock(ForVerifyingSshCredentials.class);
    private final SshTarget target =
        new SshTarget("192.168.3.50", 22, "root", AuthMethod.PASSWORD, "pw", null, null);

    @Test
    void probe_success_isReachableAndAuthenticatedWithTheFingerprint() {
        when(prober.probe(target)).thenReturn("SHA256:abc");

        SshCredentialVerification v = SshCredentialVerification.probe(target, prober);

        assertThat(v.reachable()).isTrue();
        assertThat(v.authenticated()).isTrue();
        assertThat(v.fingerprint()).isEqualTo("SHA256:abc");
    }

    @Test
    void probe_authRejected_isReachableButNotAuthenticated_andCarriesNoFingerprint() {
        when(prober.probe(target)).thenThrow(new SshAuthException("bad credential"));

        SshCredentialVerification v = SshCredentialVerification.probe(target, prober);

        assertThat(v.reachable()).isTrue();
        assertThat(v.authenticated()).isFalse();
        assertThat(v.fingerprint()).isNull();
    }

    @Test
    void probe_transportFailure_isNotReachable() {
        when(prober.probe(target)).thenThrow(new SshConnectException("no route to host"));

        SshCredentialVerification v = SshCredentialVerification.probe(target, prober);

        assertThat(v.reachable()).isFalse();
        assertThat(v.authenticated()).isFalse();
        assertThat(v.fingerprint()).isNull();
    }
}
