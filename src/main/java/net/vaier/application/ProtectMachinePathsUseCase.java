package net.vaier.application;

import net.vaier.domain.BackupJob;
import net.vaier.domain.Unprotection;
import net.vaier.domain.port.ForReadyingBackupClients.ReadyingOutcome;

import java.util.List;

/**
 * The just-select-and-back-up flow: turn a selection of paths on a machine into (or out of) a fleet-backup
 * job. All the machinery — get-or-create the machine's repository (with a backend-generated passphrase) and
 * job, then fold the selection into the job's protected {@link net.vaier.domain.SourcePaths} — lives behind
 * this one narrow port so the Explorer only has to say "protect these" / "stop protecting these".
 */
public interface ProtectMachinePathsUseCase {

    /**
     * Protect {@code paths} on {@code machineName}: get-or-create its repository and job, add the paths
     * (normalized), and return the updated job together with any host-readying outcome. Rejects with a
     * conflict when no backup server is designated.
     *
     * <p>On a machine's FIRST back-up (a newly-created job) the job decides its host must be readied and the
     * outcome rides back on {@link ProtectionOutcome#readying()}; when the machine already had a job the
     * readying is {@code null} (adding paths never re-provisions a ready host).
     */
    ProtectionOutcome protect(String machineName, List<String> paths);

    /**
     * Stop protecting {@code paths} on {@code machineName}, whichever way each path relates to the protected
     * set: a protected path (and anything under it) leaves the set, while a path a <em>remaining</em>
     * protected path still covers is recorded as an exclude — the only way to stop backing up a folder inside
     * a protected ancestor. A path that is neither is left alone.
     *
     * <p>Returns the {@link Unprotection} — the honest account of what happened, including the case where
     * nothing matched and nothing changed. Callers must not report a removal the domain did not make.
     */
    Unprotection unprotect(String machineName, List<String> paths);

    /**
     * The result of a {@link #protect} call: the updated {@code job}, and the {@code readying} outcome of the
     * automatic host provisioning that a machine's FIRST back-up triggers — {@code null} when the machine
     * already had a job (readying runs only on first back-up).
     */
    record ProtectionOutcome(BackupJob job, ReadyingOutcome readying) {}
}
