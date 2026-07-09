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
        assertThat(adapter.getByName("colina27")).isEmpty();
    }

    @Test
    void roundTripsAndEncryptsPassphraseAndLocksDownFile() throws Exception {
        BackupRepository repo = new BackupRepository("colina27", "nas-borg", "./colina",
            "totally-secret-passphrase", false);

        adapter.save(repo);

        // Full round-trip through a fresh adapter: the decrypted passphrase comes back intact.
        BackupRepositoryFileAdapter fresh =
            new BackupRepositoryFileAdapter(tempDir.toString(), new SecretCipher(tempDir.toString()));
        assertThat(fresh.getByName("colina27")).contains(repo);

        Path file = tempDir.resolve("backup-repositories.yml");
        String contents = Files.readString(file);
        // The passphrase is encrypted at rest; the plaintext never touches the file.
        assertThat(contents)
            .doesNotContain("totally-secret-passphrase")
            .contains("enc:v1:")
            // Non-secret fields are stored in the clear.
            .contains("colina27")
            .contains("nas-borg")
            .contains("./colina");

        // The file is locked down to the owner only.
        String perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
        assertThat(perms).isEqualTo("rw-------");
    }

    @Test
    void roundTripsARepositoryWithNoPathOverride() {
        // A new repository derives its path from the server, so repoPath is null on disk and on read-back.
        BackupRepository repo = new BackupRepository("colina27", "nas-borg", null, "s3cr3t", false);
        adapter.save(repo);

        assertThat(adapter.getByName("colina27")).contains(repo);
    }

    @Test
    void save_sameName_replacesEntry() {
        adapter.save(new BackupRepository("colina27", "nas-borg", "./old", "p1", false));
        adapter.save(new BackupRepository("colina27", "other-server", "./new", "p2", true));

        assertThat(adapter.getAll()).containsExactly(
            new BackupRepository("colina27", "other-server", "./new", "p2", true));
    }

    @Test
    void deleteByName_removesEntry() {
        adapter.save(new BackupRepository("colina27", "nas-borg", "./a", "p1", false));
        adapter.save(new BackupRepository("other", "nas-borg", "./b", "p2", false));

        adapter.deleteByName("colina27");

        assertThat(adapter.getAll()).containsExactly(
            new BackupRepository("other", "nas-borg", "./b", "p2", false));
    }

    @Test
    void skipsEntryMissingServerName() throws Exception {
        // An entry without serverName is malformed under the slimmed shape -> skipped with a warning, so a
        // stale old-shape file (server coords, no serverName) never crashes the load.
        Path file = tempDir.resolve("backup-repositories.yml");
        Files.writeString(file, """
            repositories:
            - name: legacy
              repoPath: ./legacy
              appendOnly: false
            - name: colina27
              serverName: nas-borg
              repoPath: ./colina
              appendOnly: false
            """);

        assertThat(adapter.getAll()).containsExactly(
            new BackupRepository("colina27", "nas-borg", "./colina", null, false));
    }

    @Test
    void skipsEntryWithAnInvalidName() throws Exception {
        // A repository created before names were validated (a space in "NUC 02") is now invalid by
        // construction. The tolerant load must SKIP it with a warning, never abort the load of the whole
        // file — the valid entry alongside it still comes back.
        Path file = tempDir.resolve("backup-repositories.yml");
        Files.writeString(file, """
            repositories:
            - name: NUC 02
              serverName: nas-borg
              appendOnly: false
            - name: colina27
              serverName: nas-borg
              repoPath: ./colina
              appendOnly: false
            """);

        assertThat(adapter.getAll()).containsExactly(
            new BackupRepository("colina27", "nas-borg", "./colina", null, false));
    }

    @Test
    void getAll_emptyWhenFileMissing() {
        assertThat(adapter.getAll()).isEmpty();
    }
}
