package net.vaier.application;

import net.vaier.domain.AccessDecision;

public interface VerifyAccessUseCase {

    /**
     * Decide whether {@code email} may reach {@code host}. An unknown email is auto-created as a
     * pending entry (so it surfaces for the admin) and denied. When {@code name} (the identity
     * provider's display name) is present and non-blank it is stored/refreshed on the entry; a
     * blank or absent name never wipes an already-known one.
     */
    AccessDecision verify(String email, String host, String name);
}
