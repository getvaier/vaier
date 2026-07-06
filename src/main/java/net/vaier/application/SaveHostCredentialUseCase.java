package net.vaier.application;

import net.vaier.domain.HostCredential;

public interface SaveHostCredentialUseCase {

    /** Store (or replace) the host credential for its machine. */
    void saveHostCredential(HostCredential credential);
}
