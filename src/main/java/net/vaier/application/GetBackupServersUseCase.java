package net.vaier.application;

import net.vaier.domain.BackupServer;

import java.util.List;

public interface GetBackupServersUseCase {

    /** Every configured fleet-backup server. */
    List<BackupServer> getBackupServers();
}
