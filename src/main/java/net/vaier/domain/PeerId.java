package net.vaier.domain;

import java.util.Collection;

/**
 * A VPN peer's immutable identifier — the slug derived from the operator-typed name when the
 * peer is created. It is the peer's WireGuard config directory name, REST path segment, and
 * routing key, and never changes once assigned. The compact constructor enforces the rule, so
 * any {@code PeerId} instance is valid by construction.
 *
 * <p>The human-facing label is a separate, freely editable field (the peer's display
 * <em>name</em>, see {@code PeerConfiguration#name}). This type is the identity, not the label.
 */
public record PeerId(String value) {

    public PeerId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Peer id must not be blank");
        }
    }

    /**
     * Slugs raw operator input into a valid peer id: any character outside {@code [A-Za-z0-9_-]}
     * becomes a hyphen, runs of hyphens collapse to one, and leading/trailing hyphens are dropped.
     * Case is preserved. Does not guarantee uniqueness — see {@link #generate}.
     *
     * @throws IllegalArgumentException if the input is null or slugs to nothing
     */
    public static PeerId sanitized(String raw) {
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
        return new PeerId(cleaned);
    }

    /**
     * Generates a unique peer id from raw operator input: the {@link #sanitized} slug, with a
     * numeric suffix appended ({@code -2}, {@code -3}, …) when the bare slug — or a previous
     * suffix — is already taken. {@code existingIds} is the set of ids already in use (null is
     * treated as empty).
     *
     * @throws IllegalArgumentException if the input is null or slugs to nothing
     */
    public static PeerId generate(String raw, Collection<String> existingIds) {
        String base = sanitized(raw).value();
        if (existingIds == null || !existingIds.contains(base)) {
            return new PeerId(base);
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = base + "-" + suffix;
            if (!existingIds.contains(candidate)) {
                return new PeerId(candidate);
            }
        }
    }

    /**
     * Renders a stored peer id for humans: the hyphens that {@link #sanitized} substitutes for
     * spaces (and other illegal characters) read back as spaces. This is the default display
     * label for peers created before the id/name split, which carry no stored name yet. Lossy by
     * design — a hyphen the operator typed deliberately is indistinguishable from a generated one.
     */
    public static String display(String id) {
        return id == null ? null : id.replace('-', ' ');
    }

    /** This id rendered for humans. See {@link #display(String)}. */
    public String display() {
        return display(value);
    }

    /** True when {@code other} is this same id — i.e. it identifies the same peer. */
    public boolean isSameAs(String other) {
        return value.equals(other);
    }

    @Override
    public String toString() {
        return value;
    }
}
