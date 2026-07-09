package net.vaier.application;

public interface DeleteBackupRepositoryUseCase {

    /** Remove the fleet-backup repository named {@code name}; a no-op when none exists. */
    void deleteBackupRepository(String name);
}
