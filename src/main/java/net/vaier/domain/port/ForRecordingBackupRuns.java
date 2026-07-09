package net.vaier.domain.port;

import net.vaier.domain.BackupRun;

import java.util.List;
import java.util.Optional;

/** Driven port for recording {@link BackupRun}s and reading back a job's history. */
public interface ForRecordingBackupRuns {

    /** Record {@code run} as the outcome (or in-flight state) of a backup execution. */
    void record(BackupRun run);

    /** The most recent run recorded for {@code jobName}, or empty when the job has never run. */
    Optional<BackupRun> latestForJob(String jobName);

    /** Every recorded run. */
    List<BackupRun> getAll();
}
