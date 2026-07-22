package net.vaier.adapter.driven;

import net.vaier.domain.SetupToken;
import net.vaier.domain.port.ForVendingSetupTokens;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for {@link SetupToken}s, keyed by the token's opaque value. Tokens are short-lived
 * (~15 min) and single-use, so a bounded, process-local map is the right home — there is nothing to
 * persist across restarts (a spent or lost token is simply regenerated from Vaier).
 *
 * <p>The authorization decision is delegated to {@link SetupToken#authorizes(String, long)}; this
 * adapter only mints random values, stores them, and removes-on-redeem so a token can never be spent
 * twice.
 */
@Component
public class InMemorySetupTokenStore implements ForVendingSetupTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final Map<String, SetupToken> tokensByValue = new ConcurrentHashMap<>();

    @Override
    public SetupToken issue(String peerId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String value = ENCODER.encodeToString(bytes);
        SetupToken token = SetupToken.issue(peerId, value, System.currentTimeMillis());
        tokensByValue.put(value, token);
        dropExpired();
        return token;
    }

    @Override
    public boolean consume(String peerId, String value) {
        if (value == null) {
            return false;
        }
        // Single-use: remove first so the token can never be redeemed twice, then let the domain
        // decide whether what we removed authorizes this peer at this instant.
        SetupToken token = tokensByValue.remove(value);
        return token != null && token.authorizes(peerId, System.currentTimeMillis());
    }

    /** Opportunistically evicts expired tokens so the map stays bounded. */
    private void dropExpired() {
        long now = System.currentTimeMillis();
        tokensByValue.values().removeIf(t -> now >= t.expiresAtEpochMs());
    }
}
