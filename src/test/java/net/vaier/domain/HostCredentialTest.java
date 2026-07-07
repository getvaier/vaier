package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostCredentialTest {

    @Test
    void constructs_aValidPasswordCredential() {
        HostCredential c = new HostCredential("nas", "admin", AuthMethod.PASSWORD, "s3cret", null, false);

        assertThat(c.machineName()).isEqualTo("nas");
        assertThat(c.username()).isEqualTo("admin");
        assertThat(c.authMethod()).isEqualTo(AuthMethod.PASSWORD);
        assertThat(c.secret()).isEqualTo("s3cret");
        assertThat(c.passphrase()).isNull();
        assertThat(c.managed()).isFalse();
    }

    @Test
    void rejects_blankMachineName() {
        assertThatThrownBy(() -> new HostCredential(" ", "admin", AuthMethod.PASSWORD, "s3cret", null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_nullUsername() {
        assertThatThrownBy(() -> new HostCredential("nas", null, AuthMethod.PASSWORD, "s3cret", null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_blankSecret() {
        assertThatThrownBy(() -> new HostCredential("nas", "admin", AuthMethod.PASSWORD, "  ", null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_nullAuthMethod() {
        assertThatThrownBy(() -> new HostCredential("nas", "admin", null, "s3cret", null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reKeyedTo_changesOnlyTheMachineName() {
        HostCredential original = new HostCredential("old-nas", "admin", AuthMethod.PRIVATE_KEY,
            "-----BEGIN KEY-----", "keypass", false);

        HostCredential reKeyed = original.reKeyedTo("new-nas");

        assertThat(reKeyed).isEqualTo(new HostCredential("new-nas", "admin", AuthMethod.PRIVATE_KEY,
            "-----BEGIN KEY-----", "keypass", false));
    }

    @Test
    void toView_redactsSecretAndPassphrase_butReportsSecretPresence() {
        HostCredential c = new HostCredential("nas", "admin", AuthMethod.PRIVATE_KEY,
            "-----BEGIN KEY-----", "keypass", false);

        HostCredentialView view = c.toView();

        assertThat(view).isEqualTo(new HostCredentialView("nas", "admin", AuthMethod.PRIVATE_KEY, true));
    }
}
