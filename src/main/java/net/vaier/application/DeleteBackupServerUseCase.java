package net.vaier.application;

public interface DeleteBackupServerUseCase {

    /** Remove the fleet-backup server named {@code name}; a no-op when none exists. */
    void deleteBackupServer(String name);
}
