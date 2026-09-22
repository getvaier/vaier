package net.vaier.domain;

import java.util.List;

/**
 * Everything the fleet nudge ladder is decided from, gathered at the driving edge from state Vaier already
 * caches — the ladder never reaches a machine to make up its mind.
 */
public record FleetSignals(List<Machine> machines, List<PublishableService> publishable, int publishedCount,
                           BackupFleet fleet, int repositoryCount, boolean survivalKitWritten,
                           List<AccessEntry> accessEntries, boolean smtpConfigured) {

    public FleetSignals {
        machines = machines == null ? List.of() : List.copyOf(machines);
        publishable = publishable == null ? List.of() : List.copyOf(publishable);
        fleet = fleet == null ? new BackupFleet(List.of()) : fleet;
        accessEntries = accessEntries == null ? List.of() : List.copyOf(accessEntries);
    }
}
