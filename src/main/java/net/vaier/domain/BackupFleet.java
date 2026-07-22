package net.vaier.domain;

import java.util.List;

/**
 * The fleet's backup-server posture, as a thin value object over the designated {@link BackupServer}s.
 * It owns the one decision the nudge layer needs — {@link #needsBackupServer()} — so "the fleet still
 * has nowhere to back up to" is asked, never re-derived from a raw list check in a service.
 */
public record BackupFleet(List<BackupServer> servers) {

    public BackupFleet {
        servers = servers == null ? List.of() : List.copyOf(servers);
    }

    /**
     * True while no backup server has been designated. A fleet with no backup server cannot back any
     * machine up, so this is what gates the "designate a backup server" nudge; once one exists the
     * fleet no longer needs one and the nudge stops firing.
     */
    public boolean needsBackupServer() {
        return servers.isEmpty();
    }
}
