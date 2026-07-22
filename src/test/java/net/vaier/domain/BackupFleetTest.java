package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackupFleetTest {

    @Test
    void needsBackupServer_whenNoneDesignated() {
        assertThat(new BackupFleet(List.of()).needsBackupServer()).isTrue();
        assertThat(new BackupFleet(null).needsBackupServer()).isTrue();
    }

    @Test
    void doesNotNeedBackupServer_onceOneExists() {
        BackupServer server = new BackupServer("nas-borg", "nas", "192.168.3.50",
            8022, "borg", null, "/volume1/docker/borg", true);
        assertThat(new BackupFleet(List.of(server)).needsBackupServer()).isFalse();
    }
}
