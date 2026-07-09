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
    void rejectsNameWithSpacesOrShellMetacharacters() {
        // A name is used verbatim as a shell/path token in every borg command, so it is an identifier: a
        // space, a command separator, or a substitution is rejected outright at construction (surfaces 400).
        for (String bad : new String[]{"NUC 02", "a b", "a; rm -rf ~", "a$(x)", "a`x`", "a|b", "a/b", ""}) {
            assertThatThrownBy(() -> new BackupRepository(bad, "nas-borg", null, "s3cr3t", false))
                .as("name %s", bad)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsSafeIdentifierNames() {
        for (String ok : new String[]{"NUC-02", "colina27", "nas_borg"}) {
            assertThat(new BackupRepository(ok, "nas-borg", null, "s3cr3t", false).name()).isEqualTo(ok);
        }
    }

    @Test
    void sanitizedNameSlugsSpacesCollapsesRunsAndTrims() {
        assertThat(BackupRepository.sanitizedName("NUC 02")).isEqualTo("NUC-02");
        assertThat(BackupRepository.sanitizedName("  a   b  ")).isEqualTo("a-b");
        assertThat(BackupRepository.sanitizedName("-lead-and-trail-")).isEqualTo("lead-and-trail");
        assertThat(BackupRepository.sanitizedName("a;b$c")).isEqualTo("a-b-c");
        // Slugs to nothing / null -> a clear failure, mirroring PeerId.sanitized.
        assertThatThrownBy(() -> BackupRepository.sanitizedName("   "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackupRepository.sanitizedName(";;;"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackupRepository.sanitizedName(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRepoPathOverrideWithSpaceOrMetacharacter() {
        // The override legitimately holds '/' and '.', but a space or shell metacharacter is rejected.
        assertThatThrownBy(() -> new BackupRepository("colina27", "nas-borg", "/a b", "s3cr3t", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("repoPath");
        assertThatThrownBy(() -> new BackupRepository("colina27", "nas-borg", "/a;rm", "s3cr3t", false))
            .isInstanceOf(IllegalArgumentException.class);
        // A normal path override is accepted.
        assertThat(new BackupRepository("colina27", "nas-borg", "./adopted-1.0", "s3cr3t", false).repoPath())
            .isEqualTo("./adopted-1.0");
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
