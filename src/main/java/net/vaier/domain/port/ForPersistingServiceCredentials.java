package net.vaier.domain.port;

import net.vaier.domain.ServiceCredentials;

import java.util.function.UnaryOperator;

/**
 * Driven port for the service credentials store. {@link #read()} sits on the forward-auth path, so it must
 * answer from memory: no file read and no decryption per call.
 */
public interface ForPersistingServiceCredentials {

    ServiceCredentials read();

    /** Apply {@code change} to the current credentials and persist the result, as one step. */
    void update(UnaryOperator<ServiceCredentials> change);
}
