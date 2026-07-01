package net.vaier.domain.port;

import java.util.List;
import java.util.Map;

/**
 * Driven port for managing per-service access rules — the admin-facing write/list side of the
 * {@code serviceGroups} store (the read side is {@link ForResolvingServiceGroup}). A rule is the
 * <em>any-of</em> list of groups an identity may satisfy to reach a service host; an empty rule
 * means "any approved user".
 */
public interface ForPersistingServiceAccessRules {

    /**
     * Set the allowed groups (any-of) for {@code host}. The implementation normalises the list (trim,
     * drop blanks, dedupe); a normalised-empty result REMOVES the host's rule, meaning any approved
     * user may reach it.
     */
    void setAllowedGroups(String host, List<String> groups);

    /** Every configured service access rule, keyed by host. Hosts with no rule are absent. */
    Map<String, List<String>> allServiceAccessRules();
}
