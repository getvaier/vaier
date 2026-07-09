package net.vaier.adapter.driven;

import net.vaier.domain.BackupServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BackupServerFileAdapterTest {

    @TempDir
    Path tempDir;

    private BackupServerFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BackupServerFileAdapter(tempDir.toString());
    }

    private BackupServer nas() {
        return new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", true);
    }

    @Test
    void getAll_emptyWhenFileMissing() {
        assertThat(adapter.getAll()).isEmpty();
    }

    @Test
    void getByName_emptyWhenNothingStored() {
        assertThat(adapter.getByName("nas-borg")).isEmpty();
    }

    @Test
    void roundTripsThroughAFreshAdapter() {
        BackupServer server = nas();
        adapter.save(server);

        BackupServerFileAdapter fresh = new BackupServerFileAdapter(tempDir.toString());
        assertThat(fresh.getByName("nas-borg")).contains(server);
        assertThat(fresh.getAll()).containsExactly(server);
    }

    @Test
    void writesUnderTheServersRootKey() throws Exception {
        adapter.save(nas());
        String contents = Files.readString(tempDir.resolve("backup-servers.yml"));
        assertThat(contents)
            .contains("servers:")
            .contains("nas-borg")
            .contains("NAS")
            .contains("192.168.3.3")
            .contains("home/borg/backups")
            .contains("/volume1/docker/borg");
    }

    @Test
    void save_sameName_replacesEntry() {
        adapter.save(new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/old", false));
        adapter.save(new BackupServer("nas-borg", "NAS", "192.168.3.9", 8022,
            "borg", "home/borg/backups", "/new", true));

        assertThat(adapter.getAll()).containsExactly(new BackupServer("nas-borg", "NAS", "192.168.3.9",
            8022, "borg", "home/borg/backups", "/new", true));
    }

    @Test
    void deleteByName_removesEntry() {
        adapter.save(nas());
        adapter.save(new BackupServer("other", "Colina 27", "192.168.1.4", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false));

        adapter.deleteByName("nas-borg");

        assertThat(adapter.getAll()).containsExactly(new BackupServer("other", "Colina 27", "192.168.1.4",
            8022, "borg", "home/borg/backups", "/volume1/docker/borg", false));
    }

    @Test
    void malformedEntryIsSkipped() throws Exception {
        // A hand-written file with one good and one malformed (missing host) entry.
        String yaml = """
            servers:
            - name: good
              machineName: NAS
              host: 192.168.3.3
              sshPort: 8022
              borgUser: borg
              baseRepoPath: home/borg/backups
              serverDataPath: /volume1/docker/borg
              managed: true
            - name: broken
              machineName: NAS
              sshPort: 8022
            """;
        Files.writeString(tempDir.resolve("backup-servers.yml"), yaml);

        assertThat(adapter.getAll()).extracting(BackupServer::name).containsExactly("good");
    }
}
