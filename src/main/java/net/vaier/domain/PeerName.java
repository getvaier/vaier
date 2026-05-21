package net.vaier.domain;

/**
 * A VPN peer's name. The compact constructor enforces the rule, so any {@code PeerName} instance
 * is valid by construction. {@link #sanitized(String)} turns raw operator input into a valid name.
 */
public record PeerName(String value) {

    public PeerName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Peer name must not be blank");
        }
    }

    /**
     * Cleans raw operator input into a valid peer name: any character outside {@code [A-Za-z0-9_-]}
     * becomes a hyphen, runs of hyphens collapse to one, and leading/trailing hyphens are dropped.
     *
     * @throws IllegalArgumentException if the input is null or sanitises to nothing
     */
    public static PeerName sanitized(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Peer name is required");
        }
        String cleaned = raw.trim()
            .replaceAll("[^a-zA-Z0-9_-]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-|-$", "");
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("Peer name is empty after sanitisation");
        }
        return new PeerName(cleaned);
    }

    /** True when {@code other} is this same name — i.e. renaming to it would change nothing. */
    public boolean isSameAs(String other) {
        return value.equals(other);
    }

    @Override
    public String toString() {
        return value;
    }
}
