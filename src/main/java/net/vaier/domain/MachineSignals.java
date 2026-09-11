package net.vaier.domain;

import lombok.Builder;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Everything already-cached that {@link MachineNudges} needs to decide which nudges a machine raises,
 * gathered at the driving edge and handed in as one named value.
 *
 * <p><b>Why a builder.</b> The assembler used to take these as eight positional parameters — an
 * {@code int}, two adjacent {@code boolean}s, two adjacent {@code Optional}s and now two adjacent
 * {@link MachineNetworks} — which is precisely the shape where a hurried edit silently swaps two
 * same-typed arguments and nothing complains. Naming each at the call site is what makes the composition
 * readable and the swap impossible; it is the same reason {@code Machine}'s own long constructor is a
 * known hazard.
 *
 * <p>It holds no decision. Every field is a reading, and every "does this mean we should suggest
 * something?" lives on a {@link MachineNudge} factory.
 *
 * @param publishableCount    services exposed on the machine but not yet routed through Vaier
 * @param reachable           whether the machine is reachable right now (from cached signals)
 * @param hasCredential       whether Vaier already holds an SSH credential for the machine
 * @param job                 the machine's backup job, or empty when nothing on it is backed up
 * @param latestRun           the machine's most recent backup run, or empty when it has never run
 * @param fleet               the fleet's backup-server posture (drives the designate nudge)
 * @param networks            what Vaier last read off this machine's own interfaces (#333)
 * @param routingHostNetworks what Vaier last read off the host that would install a LAN route — the
 *                            Vaier server — so a network that would sever its uplink is never offered
 * @param containerStandings  where each container Vaier has watched run on this machine stands (#356)
 * @param zone                the zone the operator reads times in; UTC when nobody said
 */
@Builder
public record MachineSignals(int publishableCount, boolean reachable, boolean hasCredential,
                             Optional<BackupJob> job, Optional<BackupRun> latestRun, BackupFleet fleet,
                             MachineNetworks networks, MachineNetworks routingHostNetworks,
                             List<MachineContainerStanding> containerStandings, ZoneId zone) {

    public MachineSignals {
        job = job == null ? Optional.empty() : job;
        latestRun = latestRun == null ? Optional.empty() : latestRun;
        networks = networks == null ? MachineNetworks.unknown() : networks;
        routingHostNetworks = routingHostNetworks == null ? MachineNetworks.unknown() : routingHostNetworks;
        containerStandings = containerStandings == null ? List.of() : List.copyOf(containerStandings);
        zone = zone == null ? ZoneOffset.UTC : zone;
    }
}
