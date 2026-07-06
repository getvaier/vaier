package net.vaier.application;

public interface DeleteHostCredentialUseCase {

    /** Remove the host credential held for {@code machineName}; a no-op when none exists. */
    void deleteHostCredential(String machineName);
}
