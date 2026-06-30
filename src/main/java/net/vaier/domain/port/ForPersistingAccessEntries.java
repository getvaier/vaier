package net.vaier.domain.port;

import net.vaier.domain.AccessEntry;

import java.util.List;
import java.util.Optional;

/**
 * Driven port for the file-based access store (sibling of Authelia's {@code users_database.yml}):
 * the {@code email → role + groups} map that backs social-login authorization.
 */
public interface ForPersistingAccessEntries {

    List<AccessEntry> getEntries();

    Optional<AccessEntry> findByEmail(String email);

    /** Create the entry, or replace the existing one keyed by {@link AccessEntry#getEmail()}. */
    void upsert(AccessEntry entry);

    void delete(String email);
}
