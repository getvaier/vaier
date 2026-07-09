package net.vaier.domain.port;

import net.vaier.domain.BackupRepository;

import java.util.List;
import java.util.Optional;

/** Driven port for persisting the fleet-backup {@link BackupRepository} definitions, keyed by name. */
public interface ForPersistingBackupRepositories {

    /** Every stored backup repository. */
    List<BackupRepository> getAll();

    /** The repository named {@code name}, or empty when none is stored. */
    Optional<BackupRepository> getByName(String name);

    /** Persist {@code repository}, replacing any existing repository with the same name. */
    void save(BackupRepository repository);

    /** Remove the repository named {@code name}; a no-op when none exists. */
    void deleteByName(String name);
}
