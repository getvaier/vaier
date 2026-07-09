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
