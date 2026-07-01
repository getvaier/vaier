package net.vaier.domain;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * One known social-login identity and the access it has been granted: its {@code email}, its
 * {@link Role}, and the {@code groups} that gate per-service access. The authorization decisions
 * live here, on the entity — services only orchestrate, they do not re-implement these rules.
 */
@Data
@Builder(toBuilder = true)
public class AccessEntry {

    /**
     * Reserved names that mirror the {@link Role} (admin vs user) rather than gating a service. They
     * must never live in {@code groups}: the Role is the sole authority for admin-vs-user, and
     * {@code groups} are purely per-service access tags. Kept here so this knowledge lives in the
     * domain rather than being scattered across adapters and services.
     */
    private static final Set<String> ROLE_MIRRORING_GROUPS = Set.of("admins", "users");

    private final String email;
    private final Role role;
    private final List<String> groups;

    /**
     * The identity's display name as last reported by the identity provider (Google's {@code name}
     * claim), or {@code null} until a sign-in fills it in (e.g. a pre-approved entry). Surfaced in
     * the access overview alongside the email.
     */
    private final String name;

    /**
     * The display name this entry should carry after an authentication that presented
     * {@code incomingName}. A present, non-blank name (trimmed) refreshes the stored name; an
     * absent or blank header never wipes an already-known name. So a pre-approved entry stays
     * nameless until its first sign-in, and later sign-ins keep it current.
     */
    public String resolvedName(String incomingName) {
        if (incomingName != null && !incomingName.isBlank()) {
            return incomingName.trim();
        }
        return name;
    }

    /**
     * Whether any of this entry's {@code groups} is a role-mirroring name ({@code admins}/{@code
     * users}). Such names duplicate what {@link Role} already decides and should be stripped so
     * {@code groups} stays purely per-service.
     */
    public boolean hasRoleMirroringGroups() {
        return groups != null && groups.stream().anyMatch(ROLE_MIRRORING_GROUPS::contains);
    }

    /**
     * A copy of this entry with every role-mirroring name ({@code admins}/{@code users}) removed from
     * {@code groups}; role, email and name are unchanged. Idempotent — an entry that already carries
     * only per-service tags is returned with an equivalent (empty when {@code groups} was null) list.
     */
    public AccessEntry withoutRoleMirroringGroups() {
        List<String> cleaned = groups == null ? List.of()
                : groups.stream().filter(g -> !ROLE_MIRRORING_GROUPS.contains(g)).toList();
        return toBuilder().groups(cleaned).build();
    }

    /** Authenticated but not yet approved — no access until an admin promotes the role. */
    public boolean isPending() {
        return role == Role.PENDING;
    }

    /** A Vaier administrator — may administer the console and reach every service. */
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** Whether this identity may reach the Vaier console. Admins only. */
    public boolean mayAccessConsole() {
        return isAdmin();
    }

    /**
     * Whether this identity may reach a published service that requires {@code requiredGroup}.
     * Pending identities are always denied; admins are always allowed; an ordinary user is allowed
     * iff their groups contain {@code requiredGroup}. A null/blank {@code requiredGroup} means the
     * service only requires an authenticated, approved identity (any USER or ADMIN).
     */
    public boolean mayAccessService(String requiredGroup) {
        if (isPending()) {
            return false;
        }
        if (isAdmin()) {
            return true;
        }
        if (requiredGroup == null || requiredGroup.isBlank()) {
            return true;
        }
        return groups != null && groups.contains(requiredGroup);
    }
}
