package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
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
    void of_acceptsAndNormalisesWellFormedUuidText() {
        record Row(String description, String input, String expected) {}
        List<Row> rows = List.of(
            new Row("accepts a canonical uuid", CANONICAL, CANONICAL),
            new Row("normalises uppercase to lowercase", CANONICAL.toUpperCase(), CANONICAL),
            new Row("trims surrounding whitespace", "  " + CANONICAL + "  ", CANONICAL)
        );

        for (Row row : rows) {
            assertThat(MachineId.of(row.input()).value()).as(row.description()).isEqualTo(row.expected());
        }
    }

    @Test
    void of_rejectsBadInput() {
        record Row(String description, String input) {}
        List<Row> rows = List.of(
            new Row("null", null),
            new Row("blank", "   "),
            new Row("non-uuid text", "Apalveien 5"),
            // UUID.fromString is famously lenient — it accepts "1-1-1-1-1" and silently zero-pads it
            // into a different, valid-looking UUID. A hand-written config must never be reshaped like
            // that, so validation is by pattern, not by round-tripping through UUID.
            new Row("abbreviated uuid that java would silently expand", "1-1-1-1-1"),
            new Row("uuid with missing hyphens", CANONICAL.replace("-", "")),
            // The all-zero UUID is a placeholder, not an identity — rejected so a stub can't reach the fleet.
            new Row("the nil uuid", "00000000-0000-0000-0000-000000000000")
        );

        for (Row row : rows) {
            assertThatThrownBy(() -> MachineId.of(row.input()))
                .as(row.description())
                .isInstanceOf(IllegalArgumentException.class);
        }
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
