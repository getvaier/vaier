package net.vaier.application;

import net.vaier.domain.BackupJob;

import java.util.List;

public interface GetBackupJobsUseCase {

    /** Every configured fleet-backup job. */
    List<BackupJob> getBackupJobs();
}
