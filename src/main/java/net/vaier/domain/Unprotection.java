package net.vaier.domain;

import java.util.List;

/**
 * What "stop backing up these paths" actually did — the honest answer to the Explorer's Stop backing up.
 *
 * <p>A removal can land three ways, and the operator is entitled to know which: the paths were dropped from
 * the {@link BackupJob}'s protected set or carved out of it as {@link Excludes} ({@code job} carries the
 * changed job), the job's <em>last</em> protected path went and the job itself is gone ({@link #jobDeleted()},
 * the repository is untouched), or nothing about the request matched anything protected and nothing changed
 * at all ({@link #changed()} is false).
 *
 * <p>That last case is why this type exists. It used to be indistinguishable from a successful removal — the
 * job was saved unchanged, a {@code 200} came back and the browser cheerfully reported "Stopped backing up 1
 * item." while the folder went on being backed up every night. Telling an operator their data stopped being
 * protected when it did not is the worst thing a backup tool can say, so the outcome is modelled, not assumed.
 *
 * @param job     the job as it now stands, {@code null} when the job was deleted or the machine had none
 * @param stopped the requested paths that really did stop being backed up (empty when nothing matched)
 */
public record Unprotection(BackupJob job, List<String> stopped) {

    public Unprotection {
        stopped = stopped == null ? List.of() : List.copyOf(stopped);
    }

    /** The request matched nothing: {@code job} is unchanged (or {@code null} when the machine has none). */
    public static Unprotection nothingMatched(BackupJob job) {
        return new Unprotection(job, List.of());
    }

    /** The job changed — paths dropped from its protected set, excluded from it, or both. */
    public static Unprotection changedTo(BackupJob job, List<String> stopped) {
        return new Unprotection(job, stopped);
    }

    /** The last protected path went, so the job is gone; its repository and archives are left intact. */
    public static Unprotection jobDeleted(List<String> stopped) {
        return new Unprotection(null, stopped);
    }

    /** Whether anything actually stopped being backed up. */
    public boolean changed() {
        return !stopped.isEmpty();
    }

    /** Whether the change was the job's disappearance (nothing is protected on the machine any more). */
    public boolean jobDeleted() {
        return changed() && job == null;
    }
}
