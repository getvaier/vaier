package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupServerTest {

    private BackupServer server(String serverDataPath) {
        return new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", serverDataPath, false);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new BackupServer(" ", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void rejectsNameWithSpacesOrShellMetacharacters() {
        for (String bad : new String[]{"nas borg", "a; rm -rf ~", "a$(x)", "a/b", ""}) {
            assertThatThrownBy(() -> new BackupServer(bad, "NAS", "192.168.3.3", 8022,
                "borg", "home/borg/backups", "/volume1/docker/borg", false))
                .as("name %s", bad)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsSafeIdentifierNames() {
        for (String ok : new String[]{"NUC-02", "colina27", "nas_borg"}) {
            assertThat(new BackupServer(ok, "NAS", "192.168.3.3", 8022,
                "borg", "home/borg/backups", "/volume1/docker/borg", false).name()).isEqualTo(ok);
        }
    }

    @Test
    void sanitizedNameSlugsSpacesCollapsesRunsAndTrims() {
        assertThat(BackupServer.sanitizedName("NUC 02")).isEqualTo("NUC-02");
        assertThat(BackupServer.sanitizedName("  a   b  ")).isEqualTo("a-b");
        assertThatThrownBy(() -> BackupServer.sanitizedName("   "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackupServer.sanitizedName(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsHostWithSpaceOrMetacharacter() {
        assertThatThrownBy(() -> new BackupServer("nas-borg", "NAS", "192.168.3.3; rm", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("host");
        assertThatThrownBy(() -> new BackupServer("nas-borg", "NAS", "nas host", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("host");
        // A hostname or IPv4 literal is accepted.
        assertThat(new BackupServer("nas-borg", "NAS", "nas.local", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false).host()).isEqualTo("nas.local");
    }

    @Test
    void rejectsBorgUserWithMetacharacter() {
        assertThatThrownBy(() -> new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg;whoami", "home/borg/backups", "/volume1/docker/borg", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("borgUser");
    }

    @Test
    void rejectsBaseRepoPathWithMetacharacter() {
        assertThatThrownBy(() -> new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg backups", "/volume1/docker/borg", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("baseRepoPath");
        assertThatThrownBy(() -> new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/$(x)", "/volume1/docker/borg", false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsServerDataPathWithMetacharacterButAllowsBlank() {
        assertThatThrownBy(() -> new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker borg", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("serverDataPath");
        assertThatThrownBy(() -> new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/vol;rm", false))
            .isInstanceOf(IllegalArgumentException.class);
        // Blank/null remain allowed (validated elsewhere before use as a path).
        assertThat(new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "  ", false).serverDataPath()).isEqualTo("  ");
        assertThat(new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", null, false).serverDataPath()).isNull();
    }

    @Test
    void rejectsBlankMachineName() {
        assertThatThrownBy(() -> new BackupServer("nas-borg", " ", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("machineName");
    }

    @Test
    void rejectsBlankHost() {
        assertThatThrownBy(() -> new BackupServer("nas-borg", "NAS", " ", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("host");
    }

    @Test
    void rejectsOutOfRangePort() {
        assertThatThrownBy(() -> new BackupServer("nas-borg", "NAS", "192.168.3.3", 0,
            "borg", "home/borg/backups", "/volume1/docker/borg", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sshPort");
        assertThatThrownBy(() -> new BackupServer("nas-borg", "NAS", "192.168.3.3", 70000,
            "borg", "home/borg/backups", "/volume1/docker/borg", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sshPort");
    }

    @Test
    void defaultsBorgUserAndBaseRepoPathWhenBlank() {
        BackupServer s = new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "  ", null, "/volume1/docker/borg", false);
        assertThat(s.borgUser()).isEqualTo(BackupServer.DEFAULT_BORG_USER);
        assertThat(s.baseRepoPath()).isEqualTo(BackupServer.DEFAULT_BASE_REPO_PATH);
    }

    @Test
    void exposesDefaultConstants() {
        assertThat(BackupServer.DEFAULT_SSH_PORT).isEqualTo(8022);
        assertThat(BackupServer.DEFAULT_BORG_USER).isEqualTo("borg");
        // No leading slash: borgRepoUrl inserts the '/'.
        assertThat(BackupServer.DEFAULT_BASE_REPO_PATH).isEqualTo("home/borg/backups");
    }

    @Test
    void sshUrlPrefixRendersUserHostPort() {
        assertThat(server("/volume1/docker/borg").sshUrlPrefix())
            .isEqualTo("ssh://borg@192.168.3.3:8022");
    }

    @Test
    void authorizedKeysPathRendersUnderServerDataPath() {
        assertThat(server("/volume1/docker/borg").authorizedKeysPath())
            .isEqualTo("/volume1/docker/borg/ssh/authorized_keys");
    }

    @Test
    void authorizedKeysPathThrowsWhenServerDataPathBlank() {
        assertThatThrownBy(() -> server("  ").authorizedKeysPath())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> server(null).authorizedKeysPath())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hostKeysPathRendersUnderServerDataPath() {
        // The published host-key file the setup script writes and clients read to pin (Slice 8).
        assertThat(server("/volume1/docker/borg").hostKeysPath())
            .isEqualTo("/volume1/docker/borg/ssh/host_keys.pub");
    }

    @Test
    void hostKeysPathThrowsWhenServerDataPathBlank() {
        assertThatThrownBy(() -> server("  ").hostKeysPath())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> server(null).hostKeysPath())
            .isInstanceOf(IllegalStateException.class);
    }
}
