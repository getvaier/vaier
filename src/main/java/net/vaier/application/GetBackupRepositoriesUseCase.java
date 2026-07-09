package net.vaier.application;

import net.vaier.domain.BackupRepository;

import java.util.List;

public interface GetBackupRepositoriesUseCase {

    /** Every configured fleet-backup repository. */
    List<BackupRepository> getBackupRepositories();
}
