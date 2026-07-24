package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MachineIdTest {

    private static final String CANONICAL = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

    @Test
    void generate_producesACanonicalLowercaseUuid() {
        assertThat(MachineId.generate().value())
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void generate_producesADistinctIdEachCall() {
        Set<MachineId> ids = new HashSet<>();
        IntStream.range(0, 1000).forEach(i -> ids.add(MachineId.generate()));
        assertThat(ids).hasSize(1000);
    }

    @Test
    void of_acceptsACanonicalUuid() {
        assertThat(MachineId.of(CANONICAL).value()).isEqualTo(CANONICAL);
    }

    @Test
    void of_normalisesUppercaseToLowercase() {
        assertThat(MachineId.of(CANONICAL.toUpperCase()).value()).isEqualTo(CANONICAL);
    }

    @Test
    void of_trimsSurroundingWhitespace() {
        assertThat(MachineId.of("  " + CANONICAL + "  ").value()).isEqualTo(CANONICAL);
    }

    @Test
    void of_rejectsNull() {
        assertThatThrownBy(() -> MachineId.of(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsBlank() {
        assertThatThrownBy(() -> MachineId.of("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsNonUuidText() {
        assertThatThrownBy(() -> MachineId.of("Apalveien 5"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * {@code UUID.fromString} is famously lenient — it accepts {@code "1-1-1-1-1"} and silently
     * zero-pads it into a different, valid-looking UUID. A hand-written config must never be
     * reshaped like that, so validation is by pattern, not by round-tripping through {@code UUID}.
     */
    @Test
    void of_rejectsAbbreviatedUuidThatJavaWouldSilentlyExpand() {
        assertThatThrownBy(() -> MachineId.of("1-1-1-1-1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsUuidWithMissingHyphens() {
        assertThatThrownBy(() -> MachineId.of(CANONICAL.replace("-", "")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** The all-zero UUID is a placeholder, not an identity — rejected so a stub can't reach the fleet. */
    @Test
    void of_rejectsTheNilUuid() {
        assertThatThrownBy(() -> MachineId.of("00000000-0000-0000-0000-000000000000"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_appliesTheSameInvariant() {
        assertThatThrownBy(() -> new MachineId("not-a-uuid"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equality_isByValueSoIdsWorkAsMapKeys() {
        assertThat(MachineId.of(CANONICAL)).isEqualTo(MachineId.of(CANONICAL.toUpperCase()));
        assertThat(MachineId.of(CANONICAL)).hasSameHashCodeAs(MachineId.of(CANONICAL));
    }

    @Test
    void toString_isTheBareValueSoItReadsWellInLogsAndPaths() {
        assertThat(MachineId.of(CANONICAL)).hasToString(CANONICAL);
    }
}
