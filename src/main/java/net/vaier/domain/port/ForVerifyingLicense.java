package net.vaier.domain.port;

import net.vaier.domain.License;

import java.util.Optional;

/**
 * Driven port that turns a raw licence token into an authenticated {@link License}. The driven
 * side owns the cryptography: it verifies the token's signature against the embedded public key
 * and parses the claims. A token that is malformed, tampered with, or signed by the wrong key
 * yields {@link Optional#empty()} — the caller can then only treat the instance as Community.
 *
 * <p>This port answers <em>"is this token authentic, and what does it claim?"</em>. Whether an
 * authentic licence is still valid or grants a feature is a {@link License} (domain) decision, not
 * this port's.
 */
public interface ForVerifyingLicense {

    /** The authenticated licence, or empty when the token is absent, malformed, or not authentic. */
    Optional<License> verify(String token);
}
