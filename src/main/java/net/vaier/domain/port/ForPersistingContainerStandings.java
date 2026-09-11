package net.vaier.domain.port;

import net.vaier.domain.MachineContainerStanding;
import net.vaier.domain.MachineId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Driven port for what Vaier remembers about the fleet's containers (#356) — the memory behind
 * {@link net.vaier.domain.ContainerStandingTracker}.
 *
 * <p>Persisted for the reason {@link ForPersistingMissingDefaultRoutes} is, and more sharply: the operator
 * deploys several times a day, and the container scrape lives in memory. Held in a field, "I saw this
 * running" would be wiped by every deploy, and a container that stopped during one would look like a
 * container Vaier had never seen run — which is the one case this feature is deliberately silent about.
 *
 * <p>Only containers Vaier has actually seen running have an entry, so a fleet of stopped-on-purpose
 * containers stores nothing at all.
 *
 * <p>It also keeps each machine's <b>boot instant</b>, because that is the context that makes a missing
 * container legible — the reboot is what explains it — and it is learned on the same rounds and read back
 * at the same moments. A second store for one instant per machine would be machinery this has not earned.
 */
public interface ForPersistingContainerStandings {

    /** Every container standing Vaier holds for one machine. Empty when it holds none. */
    List<MachineContainerStanding> standingsFor(MachineId machineId);

    /** Every container standing Vaier holds, for the one fleet-wide read the Explorer makes. */
    List<MachineContainerStanding> all();

    /**
     * Replace everything Vaier remembers about this machine's containers. Whole-machine rather than
     * per-container so one answered scrape costs one write, and so a container the tracker chose to forget
     * is forgotten by being absent rather than by a second call.
     */
    void record(MachineId machineId, List<MachineContainerStanding> standings);

    /** Remember when {@code machineId} last booted. */
    void recordBoot(MachineId machineId, Instant bootedAt);

    /** When {@code machineId} last booted, or empty when no sweep has managed to read it. */
    Optional<Instant> bootOf(MachineId machineId);
}
