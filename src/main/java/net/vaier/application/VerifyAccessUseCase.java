package net.vaier.application;

import net.vaier.domain.AccessDecision;

public interface VerifyAccessUseCase {

    /**
     * Decide whether {@code email} may reach {@code host}. An unknown email is auto-created as a
     * pending entry (so it surfaces for the admin) and denied. When {@code name} (the identity
     * provider's display name) is present and non-blank it is stored/refreshed on the entry; a
     * blank or absent name never wipes an already-known one. When {@code provider} (the Dex
     * {@code connector_id}: {@code google}/{@code github}) is a recognised value it is
     * stored/refreshed as the last-used identity provider; a blank, absent, or unknown value never
     * wipes an already-known provider and never affects the decision. When {@code providerUserId}
     * (the Dex {@code federated_claims.user_id}) is present and non-blank it is stored/refreshed
     * (used to build the provider avatar URL); a blank or absent value never wipes a known one.
     */
    AccessDecision verify(String email, String host, String name, String provider, String providerUserId);
}
