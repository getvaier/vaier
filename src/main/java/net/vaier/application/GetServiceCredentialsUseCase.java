package net.vaier.application;

import net.vaier.domain.ServiceCredentials;

public interface GetServiceCredentialsUseCase {

    /** Every service credential, by published service host. Callers must never show a password. */
    ServiceCredentials getServiceCredentials();
}
