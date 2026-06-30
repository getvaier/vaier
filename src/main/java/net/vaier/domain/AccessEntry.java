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
@Builder(toBuilder = true)
public class AccessEntry {

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
