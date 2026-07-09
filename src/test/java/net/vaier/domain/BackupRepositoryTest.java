package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupRepositoryTest {

    private BackupServer server() {
        return new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new BackupRepository(" ", "nas-borg", "colina27", "s3cr3t", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void rejectsBlankServerName() {
        assertThatThrownBy(() -> new BackupRepository("colina27", " ", "colina27", "s3cr3t", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("serverName");
    }

    @Test
    void allowsNullOrBlankRepoPath() {
        // repoPath is a nullable override — a new repository derives its path from the server.
        assertThat(new BackupRepository("colina27", "nas-borg", null, "s3cr3t", false).repoPath()).isNull();
        assertThat(new BackupRepository("colina27", "nas-borg", "  ", "s3cr3t", false).repoPath()).isEqualTo("  ");
    }

    @Test
    void repoPathOnDerivesBaseSlashNameWhenNoOverride() {
        BackupRepository repo = new BackupRepository("colina27", "nas-borg", null, "s3cr3t", false);
        assertThat(repo.repoPathOn(server())).isEqualTo("home/borg/backups/colina27");

        // Blank is treated as "derive" too.
        BackupRepository blank = new BackupRepository("colina27", "nas-borg", "  ", "s3cr3t", false);
        assertThat(blank.repoPathOn(server())).isEqualTo("home/borg/backups/colina27");
    }

    @Test
    void repoPathOnHonoursExplicitOverride() {
        BackupRepository repo = new BackupRepository("colina27", "nas-borg", "./adopted", "s3cr3t", false);
        assertThat(repo.repoPathOn(server())).isEqualTo("./adopted");
    }

    @Test
    void borgRepoUrlRendersAbsoluteRemotePath() {
        // The server's baseRepoPath has NO leading slash and sshUrlPrefix ends at the port, so the URL
        // inserts exactly one '/' — producing an absolute remote path.
        BackupRepository repo = new BackupRepository("colina27", "nas-borg", null, "s3cr3t", false);
        assertThat(repo.borgRepoUrl(server()))
            .isEqualTo("ssh://borg@192.168.3.3:8022/home/borg/backups/colina27");
    }

    @Test
    void borgRepoUrlHonoursExplicitOverride() {
        BackupRepository repo = new BackupRepository("colina27", "nas-borg", "./adopted", "s3cr3t", false);
        assertThat(repo.borgRepoUrl(server())).isEqualTo("ssh://borg@192.168.3.3:8022/./adopted");
    }

    @Test
    void withPassphraseReplacesOnlyTheSecret() {
        BackupRepository repo = new BackupRepository("colina27", "nas-borg", "./adopted", null, true);
        BackupRepository withSecret = repo.withPassphrase("unlocked");
        assertThat(withSecret).isEqualTo(
            new BackupRepository("colina27", "nas-borg", "./adopted", "unlocked", true));
    }
}
