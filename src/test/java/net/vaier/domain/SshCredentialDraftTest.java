package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SshCredentialDraftTest {

    private static net.vaier.domain.MachineId mid(String name) {
        return net.vaier.domain.TestMachineIds.of(name);
    }

    @Test
    void targetAt_buildsATargetForTheAddressAndPort_withNoPinnedFingerprint() {
        // Pre-registration: the machine has never been connected to, so nothing is pinned yet.
        SshCredentialDraft draft = new SshCredentialDraft("root", AuthMethod.PASSWORD, "pw", null);

        SshTarget target = draft.targetAt("192.168.3.50", 2222);

        assertThat(target.host()).isEqualTo("192.168.3.50");
        assertThat(target.port()).isEqualTo(2222);
        assertThat(target.username()).isEqualTo("root");
        assertThat(target.authMethod()).isEqualTo(AuthMethod.PASSWORD);
        assertThat(target.secret()).isEqualTo("pw");
        assertThat(target.pinnedFingerprint()).isNull();
    }

    @Test
    void forMachine_keysAnUnmanagedCredentialToTheMachineName() {
        SshCredentialDraft draft =
            new SshCredentialDraft("admin", AuthMethod.PRIVATE_KEY, "-----BEGIN KEY-----", "keypass");

        HostCredential credential = draft.forMachine(mid("nas"));

        assertThat(credential).isEqualTo(new HostCredential(mid("nas"), "admin", AuthMethod.PRIVATE_KEY, "-----BEGIN KEY-----", "keypass", false));
    }
}
