package net.vaier.domain;

import java.util.Locale;

/**
 * The access level a social-login identity has been granted. A fresh identity lands as
 * {@link #PENDING} (authenticated but blocked, awaiting an admin's approval); an admin promotes it
 * to {@link #USER} (may reach approved services) or {@link #ADMIN} (may also administer Vaier).
 */
public enum Role {
    PENDING,
    USER,
    ADMIN;

    /** The lowercase token persisted in {@code access.yml} and surfaced over the wire. */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse a persisted/hand-edited role token. Unknown, blank or null values read as
     * {@link #PENDING} — the safe default, so a malformed entry never accidentally grants access.
     */
    public static Role fromString(String value) {
        if (value == null || value.isBlank()) {
            return PENDING;
        }
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
