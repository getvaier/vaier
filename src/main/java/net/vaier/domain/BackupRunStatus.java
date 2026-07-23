package net.vaier.domain;

/**
 * The lifecycle state of one {@link BackupRun}: {@code RUNNING} while a detached borg chain is in
 * flight, the terminal {@code SUCCESS}/{@code WARNING}/{@code INCOMPLETE}/{@code FAILED} once its exit code
 * is known, and {@code UNKNOWN} when a run can no longer be resolved (e.g. its result file vanished after a
 * grace period).
 *
 * <p>The two exit-1 outcomes are deliberately distinct, because they are not the same news:
 * <ul>
 *   <li>{@code WARNING} — borg wrote the archive and got everything it was asked for, but grumbled about
 *       something on the way (a file changed while it was being read). Not a failure, never pages.</li>
 *   <li>{@code INCOMPLETE} — borg wrote the archive but could <b>not read</b> some of the job's source files,
 *       so they are missing from it. The run "completed" and the data did not arrive, which is the worst
 *       failure mode a backup has: it looks fine until you need it. It is therefore a
 *       {@link #isFailure() failure} and pages admins like one, even though an archive exists.</li>
 * </ul>
 */
public enum BackupRunStatus {
    RUNNING,
    SUCCESS,
    WARNING,
    INCOMPLETE,
    FAILED,
    UNKNOWN;

    /** Whether this is a settled outcome that will not change on its own. */
    public boolean isTerminal() {
        return this != RUNNING;
    }

    /**
     * Whether this outcome means the backup did not get the data. {@code INCOMPLETE} counts: an archive that
     * is missing files the job protects is not a backup of them, so it must reach the operator the same way a
     * failure does rather than sit quietly in a run list.
     */
    public boolean isFailure() {
        return this == FAILED || this == UNKNOWN || this == INCOMPLETE;
    }
}
