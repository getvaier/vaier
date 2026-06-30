package net.vaier.application;

import net.vaier.domain.AccessDecision;

public interface VerifyAccessUseCase {

    /**
     * Decide whether {@code email} may reach {@code host}. An unknown email is auto-created as a
     * pending entry (so it surfaces for the admin) and denied.
     */
    AccessDecision verify(String email, String host);
}
