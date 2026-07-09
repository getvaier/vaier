package net.vaier.application;

import net.vaier.domain.BackupServer;

public interface SaveBackupServerUseCase {

    /** Store (or replace) a fleet-backup server definition. */
    void saveBackupServer(BackupServer server);
}
