package net.vaier.domain.port;

import java.util.List;

/**
 * Driven port resolving the access rule for a published service host: the <em>any-of</em> list of
 * groups an identity may satisfy to reach it. Read on the forward-auth hot path. An empty list means
 * the host has no rule — any authenticated, approved identity may reach it.
 */
public interface ForResolvingServiceGroup {

    /** The allowed groups (any-of) for {@code host}, or an empty list when the host has no rule. */
    List<String> allowedGroupsForHost(String host);
}
