package net.vaier.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Stable {@link MachineId}s for tests, derived from a machine's name.
 *
 * <p>Production never does this — an id derived from a name is exactly what this refactor removed, and
 * {@link MachineId#generate()} is the only way a real machine gets one. Tests are different: a fixture
 * that stubs "the machine called nas resolves to <em>this</em> id" and an assertion that reads the id
 * back have to agree without threading a generated value through every helper. Deriving from the name
 * keeps them agreeing, and keeps the test readable as "the credential for nas".
 */
public final class TestMachineIds {

    private TestMachineIds() {
    }

    /** The stable test id for the machine named {@code name}. */
    public static MachineId of(String name) {
        return MachineId.of(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString());
    }
}
