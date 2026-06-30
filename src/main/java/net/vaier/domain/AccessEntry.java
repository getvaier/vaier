package net.vaier.domain;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * One known social-login identity and the access it has been granted: its {@code email}, its
 * {@link Role}, and the {@code groups} that gate per-service access. The authorization decisions
 * live here, on the entity — services only orchestrate, they do not re-implement these rules.
 */
@Data
@Builder
public class AccessEntry {

    private final String email;
    private final Role role;
    private final List<String> groups;

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
