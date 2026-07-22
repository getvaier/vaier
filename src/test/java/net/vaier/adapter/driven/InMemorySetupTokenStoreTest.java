package net.vaier.adapter.driven;

import net.vaier.domain.SetupToken;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store mints and redeems setup tokens. Time-based expiry is covered by {@code SetupTokenTest}
 * (the domain owns that decision); here we prove minting, single-use consumption, and per-peer
 * binding.
 */
class InMemorySetupTokenStoreTest {

    private final InMemorySetupTokenStore store = new InMemorySetupTokenStore();

    @Test
    void issue_thenConsumeOnce_isTrue() {
        SetupToken token = store.issue("apalveien5");

        assertThat(token.peerId()).isEqualTo("apalveien5");
        assertThat(token.value()).isNotBlank();
        assertThat(store.consume("apalveien5", token.value())).isTrue();
    }

    @Test
    void secondConsume_isFalse_singleUse() {
        SetupToken token = store.issue("apalveien5");

        assertThat(store.consume("apalveien5", token.value())).isTrue();
        assertThat(store.consume("apalveien5", token.value())).isFalse();
    }

    @Test
    void consume_withWrongPeer_isFalse() {
        SetupToken token = store.issue("apalveien5");

        assertThat(store.consume("colina27", token.value())).isFalse();
    }

    @Test
    void consume_madeUpValue_isFalse() {
        store.issue("apalveien5");

        assertThat(store.consume("apalveien5", "not-a-real-token")).isFalse();
    }

    @Test
    void issue_mintsDistinctValues() {
        SetupToken a = store.issue("apalveien5");
        SetupToken b = store.issue("apalveien5");

        assertThat(a.value()).isNotEqualTo(b.value());
    }
}
