package net.vaier.adapter.driven;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.HostCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HostCredentialFileAdapterTest {

    @TempDir
    Path tempDir;

    private HostCredentialFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new HostCredentialFileAdapter(tempDir.toString(), new SecretCipher(tempDir.toString()));
    }

    @Test
    void getByMachine_emptyWhenNothingStored() {
        assertThat(adapter.getByMachine("nas")).isEmpty();
    }

    @Test
    void save_thenGetByMachine_roundTripsFullCredential() {
        HostCredential credential = new HostCredential("nas", "admin", AuthMethod.PRIVATE_KEY,
            "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----", "keypass", false);

        adapter.save(credential);

        assertThat(adapter.getByMachine("nas")).contains(credential);
    }

    @Test
    void save_roundTripsPasswordCredentialWithoutPassphrase() {
        HostCredential credential = new HostCredential("router", "root", AuthMethod.PASSWORD, "s3cret", null, false);

        adapter.save(credential);

        assertThat(adapter.getByMachine("router")).contains(credential);
    }

    @Test
    void save_encryptsSecretAtRest_fileHasNoPlaintextSecret() throws Exception {
        HostCredential credential = new HostCredential("nas", "admin", AuthMethod.PASSWORD,
            "totally-secret-password", "phrase-secret", false);

        adapter.save(credential);

        String contents = Files.readString(tempDir.resolve("host-credentials.yml"));
        assertThat(contents)
            .doesNotContain("totally-secret-password")
            .doesNotContain("phrase-secret")
            .contains("enc:v1:")
            // username, machine name and auth method are stored in the clear.
            .contains("nas")
            .contains("admin")
            .contains("PASSWORD");
    }

    @Test
    void save_sameMachine_replacesEntry() {
        adapter.save(new HostCredential("nas", "admin", AuthMethod.PASSWORD, "old", null, false));
        adapter.save(new HostCredential("nas", "root", AuthMethod.PASSWORD, "new", null, false));

        assertThat(adapter.getAll()).containsExactly(
            new HostCredential("nas", "root", AuthMethod.PASSWORD, "new", null, false));
    }

    @Test
    void deleteByMachine_removesEntry() {
        adapter.save(new HostCredential("nas", "admin", AuthMethod.PASSWORD, "s3cret", null, false));
        adapter.save(new HostCredential("router", "root", AuthMethod.PASSWORD, "pw", null, false));

        adapter.deleteByMachine("nas");

        assertThat(adapter.getAll()).containsExactly(
            new HostCredential("router", "root", AuthMethod.PASSWORD, "pw", null, false));
    }

    @Test
    void roundTripsThroughFreshAdapter() {
        adapter.save(new HostCredential("nas", "admin", AuthMethod.PRIVATE_KEY, "keydata", "kp", false));

        HostCredentialFileAdapter fresh =
            new HostCredentialFileAdapter(tempDir.toString(), new SecretCipher(tempDir.toString()));

        assertThat(fresh.getByMachine("nas"))
            .contains(new HostCredential("nas", "admin", AuthMethod.PRIVATE_KEY, "keydata", "kp", false));
    }
}
