package net.vaier.application;

import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupRun;

/**
 * Launch one fleet-backup job on demand. This is the seam the {@code BackupRestController} depends on to
 * trigger a run — every controller reaches its behaviour through a {@code *UseCase}, never a rest-layer
 * component directly. Its implementation ({@code rest/BackupRunner}) generates the run id, detaches borg
 * over SSH and records the run as {@code RUNNING}, returning at once.
 */
public interface RunBackupJobUseCase {

    /**
     * Launch {@code job} against {@code repo}, returning the recorded {@code RUNNING} {@link BackupRun}
     * (with its generated run id). A guard failure is returned as a recorded {@code FAILED} run rather
     * than thrown.
     */
    BackupRun runJob(BackupJob job, BackupRepository repo);
}
