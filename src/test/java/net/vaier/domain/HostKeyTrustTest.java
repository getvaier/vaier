package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HostKeyTrustTest {

    @Test
    void noPinnedKey_isFirstUseToPin() {
        assertThat(HostKeyTrust.evaluate(null, "SHA256:abc")).isEqualTo(HostKeyTrust.PIN_NEW);
        assertThat(HostKeyTrust.evaluate("  ", "SHA256:abc")).isEqualTo(HostKeyTrust.PIN_NEW);
    }

    @Test
    void samePinnedAndPresented_matches() {
        assertThat(HostKeyTrust.evaluate("SHA256:abc", "SHA256:abc")).isEqualTo(HostKeyTrust.MATCH);
    }

    @Test
    void differentPresented_mismatches() {
        assertThat(HostKeyTrust.evaluate("SHA256:abc", "SHA256:xyz")).isEqualTo(HostKeyTrust.MISMATCH);
    }

    @Test
    void trustedMeansMatchOrPinNew_notMismatch() {
        assertThat(HostKeyTrust.evaluate(null, "k").isTrusted()).isTrue();
        assertThat(HostKeyTrust.evaluate("k", "k").isTrusted()).isTrue();
        assertThat(HostKeyTrust.evaluate("k", "other").isTrusted()).isFalse();
    }
}
