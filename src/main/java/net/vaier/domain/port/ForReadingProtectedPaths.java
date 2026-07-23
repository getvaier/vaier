package net.vaier.domain.port;

import net.vaier.domain.ProtectedPaths;

/**
 * Driven port for reading what a machine already backs up — its {@link ProtectedPaths}: the source paths a
 * fleet-backup job protects, minus the holes its excludes carve out. The Explorer uses it to mark each
 * browsed entry, without the read side having to know anything about backup jobs. A machine with no job
 * protects nothing (an empty set).
 *
 * <p>Both halves travel together on purpose. An answer built from the source paths alone would report an
 * excluded folder as backed up — a claim about data that is in no archive.
 */
public interface ForReadingProtectedPaths {

    /** What {@code machineName} currently backs up, or an empty protection when it has no job. */
    ProtectedPaths protectedPathsFor(String machineName);
}
