package net.vaier.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A machine's identity — an opaque, generated UUID that never changes and carries no meaning.
 *
 * <p>Every machine Vaier manages has one, whatever kind it is: a WireGuard {@link MachineType#isVpnPeer()
 * peer}, a {@link MachineType#LAN_SERVER}, or the Vaier server host itself. It is the key everything
 * about a machine hangs off — its credential, its host-key pin, its backup jobs, its disk watches — so
 * that renaming a machine is what it should be: an edit to a label, touching nothing else.
 *
 * <p><b>Deliberately opaque.</b> It is not derived from the name, the address, or the machine kind.
 * Anything derived from a mutable label is a label wearing an identity's clothes, and Vaier has already
 * paid for that lesson: three config files key their records on {@code "Colina 27"}, a string that exists
 * nowhere on disk and is re-derived at read time from a directory name. An id you cannot compute is an id
 * that cannot silently change underneath its references.
 *
 * <p>Distinct from {@link PeerId}, which stays on as the WireGuard adapter's storage key — the peer's
 * config <em>directory name</em>, kept human-legible on purpose so a fleet can be debugged from a console.
 * That is a storage detail; this is identity.
 */
public record MachineId(String value) {

    /** Canonical UUID form: 8-4-4-4-12 lowercase hex. */
    private static final Pattern CANONICAL = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** The all-zero UUID — a placeholder that would otherwise pass every structural check. */
    private static final String NIL = "00000000-0000-0000-0000-000000000000";

    public MachineId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Machine id must not be blank");
        }
        if (!CANONICAL.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Machine id must be a canonical lowercase UUID: " + value);
        }
        if (NIL.equals(value)) {
            throw new IllegalArgumentException("Machine id must not be the nil UUID");
        }
    }

    /** A fresh, random machine id. The only way a machine's identity is ever minted. */
    public static MachineId generate() {
        return new MachineId(UUID.randomUUID().toString());
    }

    /**
     * Reads a stored or operator-supplied machine id, trimming surrounding whitespace and lowercasing
     * hex digits — the two harmless variations a hand-edited config file picks up.
     *
     * <p>Validation is by {@link #CANONICAL pattern}, never by round-tripping through
     * {@link UUID#fromString}: that method is lenient enough to accept {@code "1-1-1-1-1"} and silently
     * zero-pad it into a different, entirely valid-looking UUID. A typo in a config file must be a loud
     * failure, not a quiet reassignment of a machine's identity.
     *
     * @throws IllegalArgumentException if {@code raw} is null, blank, or not a canonical UUID
     */
    public static MachineId of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Machine id is required");
        }
        return new MachineId(raw.trim().toLowerCase());
    }

    /** True when {@code other} is this same id — i.e. it identifies the same machine. */
    public boolean isSameAs(MachineId other) {
        return other != null && value.equals(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
