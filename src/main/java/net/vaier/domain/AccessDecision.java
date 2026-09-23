package net.vaier.domain;

import lombok.Value;
import lombok.With;

import java.util.List;
import java.util.Optional;

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
    /** The service credential to hand the service for this person, or {@code null} when none applies. */
    @With
    ServiceCredential serviceCredential;

    /** A denial — no identity is forwarded. */
    public static AccessDecision deny() {
        return new AccessDecision(false, null, null, List.of(), null, null);
    }

    /** An approval carrying the entry's identity for downstream services. */
    public static AccessDecision allow(AccessEntry entry) {
        List<String> groups = entry.getGroups() != null ? List.copyOf(entry.getGroups()) : List.of();
        return new AccessDecision(true, entry.getEmail(), entry.getEmail(), groups, entry.getName(), null);
    }

    /** The groups rendered for the {@code Remote-Groups} header — comma-separated, no spaces. */
    public String groupsHeader() {
        return String.join(",", groups);
    }

    /**
     * The {@code Authorization} the service should receive: the service credential when one applies, else
     * whatever the client {@code presented}. Traefik strips a listed response header from the request even
     * when the check returns none, so handing the client's own back is what keeps it reaching the service.
     * A denial goes back to the browser, so it never carries one.
     */
    public Optional<String> authorizationFor(String presented) {
        if (!allowed) {
            return Optional.empty();
        }
        if (serviceCredential != null) {
            return Optional.of(serviceCredential.authorizationHeader());
        }
        return Optional.ofNullable(presented).filter(p -> !p.isBlank());
    }
}
