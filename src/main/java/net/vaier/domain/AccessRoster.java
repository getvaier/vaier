package net.vaier.domain;

import java.util.List;

/**
 * An immutable snapshot of the access store's {@link AccessEntry entries}, used to reason about the
 * "at least one admin" invariant. The console is admin-only with no fallback, so the store must never
 * reach zero admins — these predicates encode that rule in the domain, where services only orchestrate
 * around them.
 */
public final class AccessRoster {

    private final List<AccessEntry> entries;

    public AccessRoster(List<AccessEntry> entries) {
        this.entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** How many entries currently hold the {@link Role#ADMIN} role. */
    public int adminCount() {
        return (int) entries.stream().filter(AccessEntry::isAdmin).count();
    }

    /**
     * Whether {@code email} is an admin <em>and</em> the sole admin in the store — i.e. removing or
     * demoting it would leave the console with no administrator. Email matching is case-insensitive
     * so a caller need not have perfectly normalised it. A blank/unknown/non-admin email is never the
     * last admin.
     */
    public boolean isOnlyAdmin(String email) {
        if (email == null || email.isBlank() || adminCount() != 1) {
            return false;
        }
        return entries.stream()
                .anyMatch(e -> e.isAdmin() && email.equalsIgnoreCase(e.getEmail()));
    }
}
