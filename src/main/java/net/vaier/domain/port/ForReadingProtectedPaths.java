package net.vaier.domain.port;

import net.vaier.domain.SourcePaths;

/**
 * Driven port for reading which paths a machine already backs up — its protected {@link SourcePaths}. The
 * Explorer uses it to mark each browsed entry a fleet-backup job already covers, without the read side
 * having to know anything about backup jobs. A machine with no job protects nothing (an empty set).
 */
public interface ForReadingProtectedPaths {

    /** The normalized set of paths {@code machineName} currently backs up, or an empty set when it has no job. */
    SourcePaths protectedPathsFor(String machineName);
}
