package net.vaier.domain.port;

import net.vaier.domain.BackupServer;

import java.util.List;
import java.util.Optional;

/** Driven port for persisting the fleet-backup {@link BackupServer} definitions, keyed by name. */
public interface ForPersistingBackupServers {

    /** Every stored backup server. */
    List<BackupServer> getAll();

    /** The server named {@code name}, or empty when none is stored. */
    Optional<BackupServer> getByName(String name);

    /** Persist {@code server}, replacing any existing server with the same name. */
    void save(BackupServer server);

    /** Remove the server named {@code name}; a no-op when none exists. */
    void deleteByName(String name);
}
