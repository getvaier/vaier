package net.vaier.application;

import net.vaier.domain.BackupRepository;

public interface SaveBackupRepositoryUseCase {

    /** Store (or replace) a fleet-backup repository definition. */
    void saveBackupRepository(BackupRepository repository);
}
