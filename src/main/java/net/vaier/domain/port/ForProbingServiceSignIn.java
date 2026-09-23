package net.vaier.domain.port;

import net.vaier.domain.ServiceProbeAnswer;

import java.util.Optional;

/** Driven port: one plain GET of a service's backend, redirects not followed. Empty when nothing answered. */
public interface ForProbingServiceSignIn {

    Optional<ServiceProbeAnswer> probe(String url);
}
