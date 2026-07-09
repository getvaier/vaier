package net.vaier.domain;

/**
 * The lifecycle state of one {@link BackupRun}: {@code RUNNING} while a detached borg chain is in
 * flight, the terminal {@code SUCCESS}/{@code FAILED} once its exit code is known, and {@code UNKNOWN}
 * when a run can no longer be resolved (e.g. its result file vanished after a grace period).
 */
public enum BackupRunStatus {
    RUNNING,
    SUCCESS,
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
