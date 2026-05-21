package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeerNameTest {

    @Test
    void sanitized_keepsAlreadyValidName() {
        assertThat(PeerName.sanitized("media-server_01").value()).isEqualTo("media-server_01");
    }

    @Test
    void sanitized_replacesInvalidCharactersWithHyphen() {
        assertThat(PeerName.sanitized("media server!!").value()).isEqualTo("media-server");
    }

    @Test
    void sanitized_collapsesRepeatedHyphens() {
        assertThat(PeerName.sanitized("a   b").value()).isEqualTo("a-b");
    }

    @Test
    void sanitized_stripsLeadingAndTrailingHyphens() {
        assertThat(PeerName.sanitized("  -nas-  ").value()).isEqualTo("nas");
    }

    @Test
    void sanitized_throwsWhenInputIsNull() {
        assertThatThrownBy(() -> PeerName.sanitized(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sanitized_throwsWhenInputSanitisesToNothing() {
        assertThatThrownBy(() -> PeerName.sanitized("!!!"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsBlankValue() {
        assertThatThrownBy(() -> new PeerName("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isSameAs_trueOnlyForIdenticalName() {
        PeerName name = PeerName.sanitized("nas");
        assertThat(name.isSameAs("nas")).isTrue();
        assertThat(name.isSameAs("nas2")).isFalse();
    }
}
