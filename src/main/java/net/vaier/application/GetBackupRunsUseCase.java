package net.vaier.application;

import net.vaier.domain.BackupRun;

import java.util.Optional;

/**
 * Read the recorded outcome of a fleet-backup job. Served purely from the {@code ForRecordingBackupRuns}
 * driven port by {@code BackupService} — no SSH, no rest-layer orchestration.
 */
public interface GetBackupRunsUseCase {

    /** The most recent {@link BackupRun} recorded for {@code jobName}, or empty when it has never run. */
    Optional<BackupRun> latestForJob(String jobName);
}
