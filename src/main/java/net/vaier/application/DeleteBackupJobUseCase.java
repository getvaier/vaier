package net.vaier.application;

public interface DeleteBackupJobUseCase {

    /** Remove the fleet-backup job named {@code name}; a no-op when none exists. */
    void deleteBackupJob(String name);
}
