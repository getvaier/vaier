package net.vaier.domain;

import lombok.Value;

import java.util.List;

/**
 * The outcome of an authorization check: whether the request is allowed and, when it is, the
 * identity headers ({@code Remote-User} / {@code Remote-Email} / {@code Remote-Groups} /
 * {@code Remote-Name}) the forward-auth endpoint emits downstream to the protected service.
 */
@Value
public class AccessDecision {

    boolean allowed;
    String user;
    String email;
    List<String> groups;
    /** The identity's display name, or {@code null} for a denial or a still-nameless (pre-approved) entry. */
    String name;

    /** A denial — no identity is forwarded. */
    public static AccessDecision deny() {
        return new AccessDecision(false, null, null, List.of(), null);
    }

    /** An approval carrying the entry's identity for downstream services. */
    public static AccessDecision allow(AccessEntry entry) {
        List<String> groups = entry.getGroups() != null ? List.copyOf(entry.getGroups()) : List.of();
        return new AccessDecision(true, entry.getEmail(), entry.getEmail(), groups, entry.getName());
    }

    /** The groups rendered for the {@code Remote-Groups} header — comma-separated, no spaces. */
    public String groupsHeader() {
        return String.join(",", groups);
    }
}
