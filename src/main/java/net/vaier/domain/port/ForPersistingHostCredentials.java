package net.vaier.domain.port;

import net.vaier.domain.HostCredential;

import java.util.List;
import java.util.Optional;

/** Driven port for storing the one host credential Vaier holds per machine in the credential vault. */
public interface ForPersistingHostCredentials {

    /** Persist {@code credential}, replacing any existing credential for the same machine. */
    void save(HostCredential credential);

    /** The credential held for {@code machineName}, or empty when none is stored. */
    Optional<HostCredential> getByMachine(String machineName);

    /** Remove the credential held for {@code machineName}; a no-op when none exists. */
    void deleteByMachine(String machineName);

    /** Every stored host credential. */
    List<HostCredential> getAll();
}
