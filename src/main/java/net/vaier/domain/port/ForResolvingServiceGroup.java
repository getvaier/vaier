package net.vaier.domain.port;

import java.util.Optional;

/**
 * Driven port resolving which access group a published service host requires. An empty result means
 * the host has no group requirement — any authenticated, approved identity may reach it.
 */
public interface ForResolvingServiceGroup {

    Optional<String> requiredGroupForHost(String host);
}
