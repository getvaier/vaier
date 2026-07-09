package net.vaier.application;

import net.vaier.domain.BackupRun;

public interface NotifyAdminsOfBackupFailureUseCase {

    /** Alert admins that a backup job's run failed — sent once when the job crosses from healthy to failing. */
    void notifyAdminsOfBackupFailure(BackupRun run);

    /** Tell admins a previously failing backup job has succeeded again — the all-clear. */
    void notifyAdminsOfBackupRecovery(BackupRun run);
}
