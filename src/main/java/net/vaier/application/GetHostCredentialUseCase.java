package net.vaier.application;

import net.vaier.domain.HostCredentialView;

import java.util.Optional;

public interface GetHostCredentialUseCase {

    /** The redacted view of the credential held for {@code machineName}, or empty when none exists. */
    Optional<HostCredentialView> getHostCredential(String machineName);
}
