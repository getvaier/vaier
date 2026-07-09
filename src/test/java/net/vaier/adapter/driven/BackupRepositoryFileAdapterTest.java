package net.vaier.adapter.driven;

import net.vaier.domain.BackupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class BackupRepositoryFileAdapterTest {

    @TempDir
    Path tempDir;

    private BackupRepositoryFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BackupRepositoryFileAdapter(tempDir.toString(), new SecretCipher(tempDir.toString()));
    }

    @Test
    void getByName_emptyWhenNothingStored() {
        assertThat(adapter.getByName("nas-borg")).isEmpty();
    }

    @Test
    void roundTripsAndEncryptsPassphraseAndLocksDownFile() throws Exception {
        BackupRepository repo = new BackupRepository("nas-borg", "192.168.3.3", 8022, "borg",
            "./colina", "totally-secret-passphrase", false);

        adapter.save(repo);

        // Full round-trip through a fresh adapter: the decrypted passphrase comes back intact.
        BackupRepositoryFileAdapter fresh =
            new BackupRepositoryFileAdapter(tempDir.toString(), new SecretCipher(tempDir.toString()));
        assertThat(fresh.getByName("nas-borg")).contains(repo);

        Path file = tempDir.resolve("backup-repositories.yml");
        String contents = Files.readString(file);
        // The passphrase is encrypted at rest; the plaintext never touches the file.
        assertThat(contents)
            .doesNotContain("totally-secret-passphrase")
            .contains("enc:v1:")
            // Non-secret fields are stored in the clear.
            .contains("nas-borg")
            .contains("192.168.3.3")
            .contains("borg")
            .contains("./colina");

        // The file is locked down to the owner only.
        String perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
        assertThat(perms).isEqualTo("rw-------");
    }

    @Test
    void save_sameName_replacesEntry() {
        adapter.save(new BackupRepository("nas-borg", "192.168.3.3", 8022, "borg", "./old", "p1", false));
        adapter.save(new BackupRepository("nas-borg", "192.168.3.9", 8022, "borg", "./new", "p2", true));

        assertThat(adapter.getAll()).containsExactly(
            new BackupRepository("nas-borg", "192.168.3.9", 8022, "borg", "./new", "p2", true));
    }

    @Test
    void deleteByName_removesEntry() {
        adapter.save(new BackupRepository("nas-borg", "192.168.3.3", 8022, "borg", "./a", "p1", false));
        adapter.save(new BackupRepository("other", "192.168.3.4", 8022, "borg", "./b", "p2", false));

        adapter.deleteByName("nas-borg");

        assertThat(adapter.getAll()).containsExactly(
            new BackupRepository("other", "192.168.3.4", 8022, "borg", "./b", "p2", false));
    }

    @Test
    void getAll_emptyWhenFileMissing() {
        assertThat(adapter.getAll()).isEmpty();
    }
}
