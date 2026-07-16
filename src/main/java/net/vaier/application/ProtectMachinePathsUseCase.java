package net.vaier.application;

import net.vaier.domain.BackupJob;

import java.util.List;
import java.util.Optional;

/**
 * The just-select-and-back-up flow: turn a selection of paths on a machine into (or out of) a fleet-backup
 * job. All the machinery — get-or-create the machine's repository (with a backend-generated passphrase) and
 * job, then fold the selection into the job's protected {@link net.vaier.domain.SourcePaths} — lives behind
 * this one narrow port so the Explorer only has to say "protect these" / "stop protecting these".
 */
public interface ProtectMachinePathsUseCase {

    /**
     * Protect {@code paths} on {@code machineName}: get-or-create its repository and job, add the paths
     * (normalized), and return the updated job. Rejects with a conflict when no backup server is designated.
     */
    BackupJob protect(String machineName, List<String> paths);

    /**
     * Stop protecting {@code paths} on {@code machineName}: remove them (and any descendants) from the
     * machine's job. Returns the updated job, or empty when the machine has no job or the job's last path
     * was removed (in which case the job is deleted, leaving the repository intact).
     */
    Optional<BackupJob> unprotect(String machineName, List<String> paths);
}
