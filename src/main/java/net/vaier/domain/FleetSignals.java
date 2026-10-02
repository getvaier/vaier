package net.vaier.domain;

import lombok.Builder;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Everything Needs you is decided from, gathered at the driving edge from state Vaier already caches — the
 * ladder never reaches a machine to make up its mind. Anything left out reads as nothing to say.
 *
 * @param lanReachability by LAN address, as the reachability probe last found it
 * @param lanLastSeen     by LAN address, the epoch second the machine last answered; absent when it never has
 * @param latestRuns      each backup job's latest run
 * @param containers      by machine identity, the containers Vaier last scraped there
 */
@Builder
public record FleetSignals(List<Machine> machines, List<PublishableService> publishable, int publishedCount,
                           BackupFleet fleet, int repositoryCount, boolean survivalKitWritten,
                           List<AccessEntry> accessEntries, boolean smtpConfigured,
                           MachineId vaierServer, Map<String, Reachability> lanReachability,
                           Map<String, Long> lanLastSeen, List<BackupJob> backupJobs, List<BackupRun> latestRuns,
                           List<MachineDiskStanding> diskStandings, Map<String, List<DockerService>> containers,
                           List<EnrolmentRequest> enrolmentRequests, PreFlight preFlight,
                           ReverseProxyAudit routeAudit, ZoneId zone) {

    public FleetSignals {
        machines = machines == null ? List.of() : List.copyOf(machines);
        publishable = publishable == null ? List.of() : List.copyOf(publishable);
        fleet = fleet == null ? new BackupFleet(List.of()) : fleet;
        accessEntries = accessEntries == null ? List.of() : List.copyOf(accessEntries);
        lanReachability = lanReachability == null ? Map.of() : Map.copyOf(lanReachability);
        lanLastSeen = lanLastSeen == null ? Map.of() : Map.copyOf(lanLastSeen);
        backupJobs = backupJobs == null ? List.of() : List.copyOf(backupJobs);
        latestRuns = latestRuns == null ? List.of() : List.copyOf(latestRuns);
        diskStandings = diskStandings == null ? List.of() : List.copyOf(diskStandings);
        containers = containers == null ? Map.of() : Map.copyOf(containers);
        enrolmentRequests = enrolmentRequests == null ? List.of() : List.copyOf(enrolmentRequests);
        preFlight = preFlight == null ? new PreFlight(List.of()) : preFlight;
        routeAudit = routeAudit == null ? new ReverseProxyAudit(List.of()) : routeAudit;
        zone = zone == null ? ZoneOffset.UTC : zone;
    }
}
