package net.vaier.domain;

import java.time.Duration;

/**
 * A single-use, short-lived, per-peer bearer token — the ONLY authorization on the anonymous
 * {@code GET /vpn/peers/{id}/setup} route (Slice 4b of "add a machine"). A bare box being
 * onboarded has no oauth2 session, so the token stands in for one: it is minted when the peer's
 * config is delivered, embedded in the {@code curl … | sh} one-liner the operator pastes on the
 * box, validated in Vaier, and burned on first use.
 *
 * <p>Its safety rests on being narrow in every dimension — bound to one peer, valid for {@link #TTL}
 * only, and consumed exactly once (see {@code ForVendingSetupTokens}). It also burns the one-shot
 * config-retrieval budget (#202), so an intercepted link that is spent leaves the box unable to come
 * up — the operator notices and regenerates.
 *
 * <p>Whether a presented token authorizes a given peer at a given instant is the domain decision
 * that lives here on {@link #authorizes(String, long)} — never in the controller or the store.
 */
public record SetupToken(String peerId, String value, long expiresAtEpochMs) {

    /** How long a freshly issued token remains valid. */
    public static final Duration TTL = Duration.ofMinutes(15);

    /** Mints a token for {@code peerId} that expires {@link #TTL} after {@code nowEpochMs}. */
    public static SetupToken issue(String peerId, String value, long nowEpochMs) {
        return new SetupToken(peerId, value, nowEpochMs + TTL.toMillis());
    }

    /**
     * The authorization decision: true iff this token is for {@code requestedPeerId} and has not
     * yet expired at {@code nowEpochMs}. Expiry is exclusive — a token is dead at its expiry instant.
     */
    public boolean authorizes(String requestedPeerId, long nowEpochMs) {
        return peerId.equals(requestedPeerId) && nowEpochMs < expiresAtEpochMs;
    }
}
