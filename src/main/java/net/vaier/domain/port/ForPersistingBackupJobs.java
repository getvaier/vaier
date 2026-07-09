package net.vaier.domain.port;

import net.vaier.domain.BackupJob;

import java.util.List;
import java.util.Optional;

/** Driven port for persisting the fleet-backup {@link BackupJob} definitions, keyed by name. */
public interface ForPersistingBackupJobs {

    /** Every stored backup job. */
    List<BackupJob> getAll();

    /** The job named {@code name}, or empty when none is stored. */
    Optional<BackupJob> getByName(String name);

    /** Every job that backs up the machine named {@code machineName}. */
    List<BackupJob> getByMachine(String machineName);

    /** Persist {@code job}, replacing any existing job with the same name. */
    void save(BackupJob job);

    /** Remove the job named {@code name}; a no-op when none exists. */
    void deleteByName(String name);
}
