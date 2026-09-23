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

    /**
     * The first-run claim: a sign-in through Dex's local connector — the first-run password, which
     * exists only while no identity provider is configured — becomes the admin while the store has
     * none. Once any admin exists the door is closed for everyone, and a provider sign-in never
     * claims: that is the pending path.
     */
    public boolean claimsFirstAdmin(String provider) {
        return provider != null
                && AccessEntry.LOCAL_CONNECTOR.equalsIgnoreCase(provider.trim())
                && adminCount() == 0;
    }

    /**
     * Whether the configured admin must be seeded: no admin at all, or a provider has closed the
     * first-run door and every admin is a first-run account that can no longer sign in.
     */
    public boolean needsConfiguredAdmin(boolean providerConfigured) {
        return entries.stream().filter(AccessEntry::isAdmin)
                .allMatch(admin -> providerConfigured && admin.isFirstRunAccount());
    }

    /** Admins exist, but none of them can sign in any more — as opposed to a store that never had one. */
    public boolean isLockedOut(boolean providerConfigured) {
        return adminCount() > 0 && needsConfiguredAdmin(providerConfigured);
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
