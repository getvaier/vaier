package net.vaier.domain.port;

import net.vaier.domain.SetupToken;

/**
 * Driven port for minting and redeeming {@link SetupToken}s — the single-use bearer tokens that
 * authorize the anonymous {@code GET /vpn/peers/{id}/setup} route (Slice 4b). The store keeps
 * tokens; the authorization decision itself lives on {@link SetupToken#authorizes(String, long)}.
 *
 * <p>Implemented only by an {@code *Adapter} (a store) — never by a service.
 */
public interface ForVendingSetupTokens {

    /**
     * Mints a fresh token for {@code peerId}: a cryptographically-random value, a ~15-minute TTL,
     * stored for later redemption, and returned so the caller can embed its {@code value()} in the
     * setup one-liner.
     */
    SetupToken issue(String peerId);

    /**
     * Redeems the token with this {@code value} exactly once (single-use): atomically removes it and
     * returns whether it authorizes {@code peerId} at the current instant. Returns {@code false} for
     * an unknown value, a value minted for a different peer, or an expired token — and a second
     * redemption of the same value always returns {@code false} because it is already gone.
     */
    boolean consume(String peerId, String value);
}
