package net.vaier.domain;

/**
 * The lifecycle state of one {@link BackupRun}: {@code RUNNING} while a detached borg chain is in
 * flight, the terminal {@code SUCCESS}/{@code WARNING}/{@code FAILED} once its exit code is known, and
 * {@code UNKNOWN} when a run can no longer be resolved (e.g. its result file vanished after a grace
 * period). {@code WARNING} means the backup completed, some files were skipped (borg exit 1) — the
 * archive was still created, so it is terminal but not a failure and never pages admins.
 */
public enum BackupRunStatus {
    RUNNING,
    SUCCESS,
    WARNING,
    FAILED,
    UNKNOWN;

    /** Whether this is a settled outcome that will not change on its own. */
    public boolean isTerminal() {
        return this != RUNNING;
    }

    /** Whether this outcome means the backup did not complete successfully. */
    public boolean isFailure() {
        return this == FAILED || this == UNKNOWN;
    }
}
