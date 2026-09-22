package net.vaier.domain;

import lombok.Builder;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One rung of the fleet-level nudge ladder (#336): what the operator should do next, said on the page everyone
 * lands on, with the evidence Vaier used. The same shape as a {@link MachineNudge} at fleet altitude — a title
 * stated as intent, the "why", the action taken on yes, and for the rungs that point at one machine, that
 * machine's identity as {@code value} — so the Explorer renders it with the very same card.
 *
 * <p>Pure domain: each rung's "should we say this?" is a static factory here, composed from already-cached
 * state the driving edge hands in. A rung fires only on its own condition and vanishes the moment it clears;
 * nothing is dismissed and nothing is ticked.
 */
@Builder
public record FleetNudge(Kind kind, String title, String evidence, String action, String value) {

    public enum Kind {
        /** Nothing is connected yet. */
        ADD_MACHINE,
        /** Someone signed in and is blocked, awaiting an admin's approval. */
        LET_PEOPLE_IN,
        /** Machines expose services and none is routed through Vaier yet. */
        PUBLISH,
        /** There are machines and no backup server anywhere. */
        DESIGNATE_BACKUP_SERVER,
        /** Repositories exist and no survival kit has ever been written. */
        WRITE_SURVIVAL_KIT,
        /** No SMTP server: every alert Vaier would raise is silent. */
        CONFIGURE_SMTP
    }

    public FleetNudge {
        if (kind == null) {
            throw new IllegalArgumentException("FleetNudge kind must not be null");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("FleetNudge title must not be blank");
        }
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("FleetNudge evidence must not be blank");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("FleetNudge action must not be blank");
        }
    }

    public static Optional<FleetNudge> addMachine(int machineCount) {
        if (machineCount > 0) return Optional.empty();
        return Optional.of(new FleetNudge(Kind.ADD_MACHINE, "Add the machine your services run on",
            "Nothing is connected yet", "Add a machine", null));
    }

    /** LET_PEOPLE_IN — whoever signed in and is still blocked; the entity says who is pending, not the caller. */
    public static Optional<FleetNudge> letPeopleIn(List<AccessEntry> accessEntries) {
        long pendingPeople = accessEntries.stream().filter(AccessEntry::isPending).count();
        if (pendingPeople <= 0) return Optional.empty();
        String who = pendingPeople == 1 ? "1 person is" : pendingPeople + " people are";
        return Optional.of(new FleetNudge(Kind.LET_PEOPLE_IN, who + " waiting to be let in",
            "Signed in, blocked, awaiting approval", "Review them", null));
    }

    /**
     * PUBLISH — while nothing at all is published, point at the machine with the most exposed, unrouted
     * services. Once anything is published the per-machine nudges carry the rest.
     */
    public static Optional<FleetNudge> publish(List<Machine> machines, List<PublishableService> publishable,
                                               int publishedCount) {
        if (publishedCount > 0 || publishable.isEmpty()) return Optional.empty();
        Map<String, Long> perMachine = publishable.stream()
            .filter(s -> s.machineId() != null && !s.ignored())
            .collect(Collectors.groupingBy(PublishableService::machineId, Collectors.counting()));
        Optional<Map.Entry<String, Long>> most = perMachine.entrySet().stream()
            .max(Map.Entry.comparingByValue());
        if (most.isEmpty()) return Optional.empty();
        Optional<Machine> target = machines.stream()
            .filter(m -> m.id() != null && m.id().value().equals(most.get().getKey()))
            .findFirst();
        if (target.isEmpty()) return Optional.empty();
        long n = most.get().getValue();
        String plural = n == 1 ? "" : "s";
        return Optional.of(new FleetNudge(Kind.PUBLISH,
            "Publish " + n + " service" + plural + " on " + target.get().name(),
            n + " exposed there, none routed through Vaier",
            "Give each an HTTPS address and a launchpad tile", target.get().id().value()));
    }

    /**
     * DESIGNATE_BACKUP_SERVER — while the fleet has machines and no backup server. Offers a NAS when the fleet
     * has one, since that is where backups usually belong; otherwise the operator chooses.
     */
    public static Optional<FleetNudge> designateBackupServer(List<Machine> machines, BackupFleet fleet) {
        if (machines.isEmpty() || !fleet.needsBackupServer()) return Optional.empty();
        Optional<Machine> nas = machines.stream()
            .filter(m -> m.deviceCategory() == DeviceCategory.NAS).findFirst();
        String count = machines.size() == 1 ? "1 machine" : machines.size() + " machines";
        String evidence = count + ", no backup server designated"
            + nas.map(m -> " — " + m.name() + " could hold it").orElse("");
        return Optional.of(new FleetNudge(Kind.DESIGNATE_BACKUP_SERVER, "Nothing in your fleet is backed up",
            evidence, "Designate a backup server", nas.map(m -> m.id().value()).orElse(null)));
    }

    public static Optional<FleetNudge> writeSurvivalKit(int repositoryCount, boolean kitWritten) {
        if (repositoryCount <= 0 || kitWritten) return Optional.empty();
        String repos = repositoryCount == 1 ? "1 repository" : repositoryCount + " repositories";
        return Optional.of(new FleetNudge(Kind.WRITE_SURVIVAL_KIT,
            "If this server died, nothing could open your backups",
            repos + ", no survival kit written", "Write the survival kit", null));
    }

    public static Optional<FleetNudge> configureSmtp(boolean smtpConfigured) {
        if (smtpConfigured) return Optional.empty();
        return Optional.of(new FleetNudge(Kind.CONFIGURE_SMTP, "Vaier cannot tell you when something breaks",
            "Disk, backup and machine alerts are all silent", "Set up mail", null));
    }
}
