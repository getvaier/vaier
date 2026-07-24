package net.vaier.application;

import net.vaier.domain.SshCredentialDraft;

public interface SaveHostCredentialUseCase {

    /**
     * Store (or replace) the host credential for the machine named {@code machineName}.
     *
     * <p>Takes the machine's <em>name</em> because that is what a REST path carries, and a
     * {@link SshCredentialDraft} because that is what an operator supplies. Turning the pair into a
     * vault credential keyed by the machine's identity is the application's job, not the caller's — a
     * controller that assembled the entity itself would be deciding which machine a login belongs to.
     *
     * @throws net.vaier.domain.NotFoundException when no machine bears that name
     */
    void saveHostCredential(String machineName, SshCredentialDraft draft);
}
