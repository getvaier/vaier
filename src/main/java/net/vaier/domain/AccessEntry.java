package net.vaier.domain;

import lombok.Builder;
import lombok.Data;

import java.util.Collection;
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
     * The identity provider (Dex connector id: {@code google} / {@code github}) this identity last
     * signed in with, or {@code null} until a sign-in fills it in (e.g. a pre-approved entry that
     * has never authenticated). Surfaced as a small provider glyph in the access overview.
     */
    private final String provider;

    /**
     * The identity's stable id at its {@link #provider} (the Dex {@code federated_claims.user_id}:
     * GitHub's numeric account id, Google's {@code sub}), or {@code null} until a sign-in fills it
     * in. Used to build a provider avatar URL (e.g. the GitHub photo) in the access overview.
     */
    private final String providerUserId;

    /** The Dex connector ids Vaier recognises as identity providers. */
    private static final Set<String> KNOWN_PROVIDERS = Set.of("google", "github");

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
     * The identity provider this entry should carry after an authentication that presented
     * {@code incomingProvider} (the Dex {@code connector_id} header). A recognised provider
     * (case-insensitive, trimmed) refreshes the stored one; a blank, absent, or unrecognised value
     * never wipes an already-known provider and never breaks the access decision. So a pre-approved
     * entry stays provider-less until its first sign-in, and later sign-ins keep it current.
     */
    public String resolvedProvider(String incomingProvider) {
        if (incomingProvider != null) {
            String normalised = incomingProvider.trim().toLowerCase(java.util.Locale.ROOT);
            if (KNOWN_PROVIDERS.contains(normalised)) {
                return normalised;
            }
        }
        return provider;
    }

    /**
     * The provider user id this entry should carry after an authentication that presented
     * {@code incomingProviderUserId} (the Dex {@code federated_claims.user_id} header). A present,
     * non-blank value (trimmed) refreshes the stored id; an absent or blank header never wipes an
     * already-known id. So a pre-approved entry stays id-less until its first sign-in.
     */
    public String resolvedProviderUserId(String incomingProviderUserId) {
        if (incomingProviderUserId != null && !incomingProviderUserId.isBlank()) {
            return incomingProviderUserId.trim();
        }
        return providerUserId;
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
     * Whether this identity may reach a published service governed by an access rule of
     * {@code allowedGroups} (an <em>any-of</em> set). Pending identities are always denied; admins
     * are always allowed; a null/empty {@code allowedGroups} means the service only requires an
     * authenticated, approved identity (any USER or ADMIN). Otherwise an ordinary user is allowed
     * iff their own groups intersect {@code allowedGroups} on at least one group.
     */
    public boolean mayAccessService(Collection<String> allowedGroups) {
        if (isPending()) {
            return false;
        }
        if (isAdmin()) {
            return true;
        }
        if (allowedGroups == null || allowedGroups.isEmpty()) {
            return true;
        }
        return groups != null && groups.stream().anyMatch(allowedGroups::contains);
    }
}
