package net.vaier.application;

import net.vaier.domain.BackupJob;
import net.vaier.domain.port.ForReadyingBackupClients.ReadyingOutcome;

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
     * (normalized), and return the updated job together with any host-readying outcome. Rejects with a
     * conflict when no backup server is designated.
     *
     * <p>On a machine's FIRST back-up (a newly-created job) the job decides its host must be readied and the
     * outcome rides back on {@link ProtectionOutcome#readying()}; when the machine already had a job the
     * readying is {@code null} (adding paths never re-provisions a ready host).
     */
    ProtectionOutcome protect(String machineName, List<String> paths);

    /**
     * Stop protecting {@code paths} on {@code machineName}: remove them (and any descendants) from the
     * machine's job. Returns the updated job, or empty when the machine has no job or the job's last path
     * was removed (in which case the job is deleted, leaving the repository intact).
     */
    Optional<BackupJob> unprotect(String machineName, List<String> paths);

    /**
     * The result of a {@link #protect} call: the updated {@code job}, and the {@code readying} outcome of the
     * automatic host provisioning that a machine's FIRST back-up triggers — {@code null} when the machine
     * already had a job (readying runs only on first back-up).
     */
    record ProtectionOutcome(BackupJob job, ReadyingOutcome readying) {}
}
