package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The setup token is the ONLY authorization on the anonymous {@code GET /vpn/peers/{id}/setup}
 * route (Slice 4b). Whether a presented token authorizes a given peer at a given moment is a pure
 * domain decision — it belongs here, on the value object, not in the controller or the store.
 */
class SetupTokenTest {

    @Test
    void authorizes_sameePeer_beforeExpiry() {
        long now = 1_000_000L;
        SetupToken token = SetupToken.issue("apalveien5", "secret-value", now);

        assertThat(token.authorizes("apalveien5", now)).isTrue();
        assertThat(token.authorizes("apalveien5", now + SetupToken.TTL.toMillis() - 1)).isTrue();
    }

    @Test
    void doesNotAuthorize_aDifferentPeer() {
        long now = 1_000_000L;
        SetupToken token = SetupToken.issue("apalveien5", "secret-value", now);

        assertThat(token.authorizes("colina27", now)).isFalse();
    }

    @Test
    void doesNotAuthorize_atOrAfterExpiry() {
        long now = 1_000_000L;
        SetupToken token = SetupToken.issue("apalveien5", "secret-value", now);
        long expiresAt = now + SetupToken.TTL.toMillis();

        assertThat(token.authorizes("apalveien5", expiresAt)).isFalse();
        assertThat(token.authorizes("apalveien5", expiresAt + 1)).isFalse();
    }

    @Test
    void issue_setsExpiryTtlAfterNow() {
        long now = 5_000L;
        SetupToken token = SetupToken.issue("apalveien5", "secret-value", now);

        assertThat(token.expiresAtEpochMs()).isEqualTo(now + SetupToken.TTL.toMillis());
        assertThat(token.peerId()).isEqualTo("apalveien5");
        assertThat(token.value()).isEqualTo("secret-value");
    }
}
