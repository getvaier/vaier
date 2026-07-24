package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostCredentialTest {

    private static net.vaier.domain.MachineId mid(String name) {
        return net.vaier.domain.TestMachineIds.of(name);
    }

    @Test
    void constructs_aValidPasswordCredential() {
        HostCredential c = new HostCredential(mid("nas"), "admin", AuthMethod.PASSWORD, "s3cret", null, false);

        assertThat(c.machineId()).isEqualTo(mid("nas"));
        assertThat(c.username()).isEqualTo("admin");
        assertThat(c.authMethod()).isEqualTo(AuthMethod.PASSWORD);
        assertThat(c.secret()).isEqualTo("s3cret");
        assertThat(c.passphrase()).isNull();
        assertThat(c.managed()).isFalse();
    }

    @Test
    void rejects_missingMachineId() {
        assertThatThrownBy(() -> new HostCredential(null, "admin", AuthMethod.PASSWORD, "s3cret", null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_nullUsername() {
        assertThatThrownBy(() -> new HostCredential(mid("nas"), null, AuthMethod.PASSWORD, "s3cret", null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_blankSecret() {
        assertThatThrownBy(() -> new HostCredential(mid("nas"), "admin", AuthMethod.PASSWORD, "  ", null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_nullAuthMethod() {
        assertThatThrownBy(() -> new HostCredential(mid("nas"), "admin", null, "s3cret", null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    void toView_redactsSecretAndPassphrase_butReportsSecretPresence() {
        HostCredential c = new HostCredential(mid("nas"), "admin", AuthMethod.PRIVATE_KEY,
            "-----BEGIN KEY-----", "keypass", false);

        HostCredentialView view = c.toView();

        assertThat(view).isEqualTo(new HostCredentialView(mid("nas"), "admin", AuthMethod.PRIVATE_KEY, true));
    }
}
