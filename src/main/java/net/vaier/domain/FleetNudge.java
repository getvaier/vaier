package net.vaier.domain;

import lombok.Builder;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One row of <b>Needs you</b> on the Fleet root: a thing that needs the operator, said in one sentence with the
 * evidence Vaier used. Two kinds share the shape. Trouble — a machine not answering, a backup that failed, a
 * disk filling, Vaier's own basics — and the nudge ladder (#336), what to do next. For the rows that point at
 * one machine, that machine's identity is {@code value}; {@code detail} is what a row can open to show more.
 *
 * <p>Pure domain: each row's "should we say this?" is a static factory here, composed from already-cached
 * state the driving edge hands in. A row appears only on its own condition and vanishes the moment it clears;
 * nothing is dismissed and nothing is ticked.
 */
@Builder
public record FleetNudge(Kind kind, String title, String evidence, String action, String value,
                         List<String> detail) {

    /** How loud a row is: a verdict, something worth an eye, or an invitation. */
    public enum Tone { VERDICT, WATCH, INVITE }

    /** Declaration order is reading order: what is most urgent first. */
    public enum Kind {
        /** A device asked to join and is showing its code right now. */
        DEVICE_WAITING(false, Tone.INVITE),
        /** A pre-flight finding: one of Vaier's own basics is wrong. */
        VAIER_BASICS(false, Tone.VERDICT),
        /** A server that should be answering is not. */
        MACHINE_DOWN(true, Tone.VERDICT),
        /** A machine's last backup failed. */
        BACKUP_FAILED(true, Tone.VERDICT),
        /** A machine's last backup failed only because it has no borg client yet. */
        BACKUP_NEEDS_READYING(true, Tone.VERDICT),
        /** A machine's last backup lost files to permissions, and reading them as root would get them. */
        BACK_UP_AS_ROOT(true, Tone.VERDICT),
        /** A machine's last backup is missing files, for a reason Vaier cannot fix. */
        BACKUP_INCOMPLETE(true, Tone.VERDICT),
        /** A machine's disk is past its threshold. */
        DISK_FULL(true, Tone.VERDICT),
        /** A machine's disk is closing on its threshold. */
        DISK_FILLING(true, Tone.WATCH),
        /** The reverse proxy audit found entries no route can reach. */
        ROUTE_AUDIT(false, Tone.WATCH),
        /** Someone signed in and is blocked, awaiting an admin's approval. */
        LET_PEOPLE_IN(false, Tone.INVITE),
        /** Nothing is connected yet. */
        ADD_MACHINE(false, Tone.INVITE),
        /** Machines expose services and none is routed through Vaier yet. */
        PUBLISH(false, Tone.INVITE),
        /** There are machines and no backup server anywhere. */
        DESIGNATE_BACKUP_SERVER(false, Tone.INVITE),
        /** Repositories exist and no survival kit has ever been written. */
        WRITE_SURVIVAL_KIT(false, Tone.INVITE),
        /** No SMTP server: every alert Vaier would raise is silent. */
        CONFIGURE_SMTP(false, Tone.INVITE),
        /** Containers on a machine have a newer image. Worth knowing, never urgent. */
        IMAGE_UPDATES(false, Tone.WATCH);

        private final boolean trouble;
        private final Tone tone;

        Kind(boolean trouble, Tone tone) {
            this.trouble = trouble;
            this.tone = tone;
        }

        public Tone tone() {
            return tone;
        }

        /** A verdict on one machine — what puts that machine first in the fleet. */
        public boolean isTrouble() {
            return trouble;
        }
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM 'at' HH:mm", Locale.ENGLISH);

    /** A line longer than this is not evidence, it is a log. */
    private static final int EVIDENCE_MAX = 120;

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
        detail = detail == null ? List.of() : List.copyOf(detail);
    }

    private static FleetNudge of(Kind kind, String title, String evidence, String action, String value) {
        return FleetNudge.builder().kind(kind).title(title).evidence(evidence).action(action).value(value).build();
    }

    public static Optional<FleetNudge> addMachine(int machineCount) {
        if (machineCount > 0) return Optional.empty();
        return Optional.of(of(Kind.ADD_MACHINE, "Add the machine your services run on",
            "Nothing is connected yet", "Add a machine", null));
    }

    /** LET_PEOPLE_IN — whoever signed in and is still blocked; the entity says who is pending, not the caller. */
    public static Optional<FleetNudge> letPeopleIn(List<AccessEntry> accessEntries) {
        long pendingPeople = accessEntries.stream().filter(AccessEntry::isPending).count();
        if (pendingPeople <= 0) return Optional.empty();
        String who = pendingPeople == 1 ? "1 person is" : pendingPeople + " people are";
        return Optional.of(of(Kind.LET_PEOPLE_IN, who + " waiting to be let in",
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
        return Optional.of(of(Kind.PUBLISH,
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
        return Optional.of(of(Kind.DESIGNATE_BACKUP_SERVER, "Nothing in your fleet is backed up",
            evidence, "Designate a backup server", nas.map(m -> m.id().value()).orElse(null)));
    }

    public static Optional<FleetNudge> writeSurvivalKit(int repositoryCount, boolean kitWritten) {
        if (repositoryCount <= 0 || kitWritten) return Optional.empty();
        String repos = repositoryCount == 1 ? "1 repository" : repositoryCount + " repositories";
        return Optional.of(of(Kind.WRITE_SURVIVAL_KIT,
            "If this server died, nothing could open your backups",
            repos + ", no survival kit written", "Write the survival kit", null));
    }

    public static Optional<FleetNudge> configureSmtp(boolean smtpConfigured) {
        if (smtpConfigured) return Optional.empty();
        return Optional.of(of(Kind.CONFIGURE_SMTP, "Vaier cannot tell you when something breaks",
            "Disk, backup and machine alerts are all silent", "Set up mail", null));
    }

    // --- trouble ---

    /** DEVICE_WAITING — one row per device showing a join code right now. */
    public static List<FleetNudge> devicesWaiting(List<EnrolmentRequest> requests) {
        return requests.stream()
            .map(r -> FleetNudge.builder().kind(Kind.DEVICE_WAITING)
                .title(r.name() + " wants to join")
                .evidence((r.machineType() == MachineType.WINDOWS_CLIENT ? "A computer" : "A phone")
                    + ", showing the code " + r.code())
                .action("Check the code matches, then add it")
                .value(r.code())
                .build())
            .toList();
    }

    /**
     * VAIER_BASICS — each pre-flight finding, in its own two sentences. The server's own disk is left to its
     * disk row, which says it once on the Vaier server's card as well.
     */
    public static List<FleetNudge> vaierBasics(PreFlight preFlight) {
        return preFlight.findings().stream()
            .filter(f -> f.check() != PreFlightFinding.Check.DISK)
            .map(f -> FleetNudge.builder().kind(Kind.VAIER_BASICS)
                .title(f.message()).evidence(f.remedy())
                .action("Vaier cannot fix this from inside; the line above says where")
                .build())
            .toList();
    }

    /** ROUTE_AUDIT — one row for the whole audit; its findings are the detail it opens. */
    public static Optional<FleetNudge> routeAudit(ReverseProxyAudit audit) {
        if (audit.isClean()) return Optional.empty();
        int n = audit.findings().size();
        return Optional.of(FleetNudge.builder().kind(Kind.ROUTE_AUDIT)
            .title(n + (n == 1 ? " entry in the reverse proxy config leads" : " entries in the reverse proxy config lead")
                + " nowhere")
            .evidence("Vaier changed nothing — removing one is yours to decide")
            .action("See which")
            .detail(audit.findings().stream().map(ReverseProxyFinding::message).toList())
            .build());
    }

    /**
     * MACHINE_DOWN — every server that should be answering and is not. A failed backup on such a machine is a
     * consequence, so it rides on this row's evidence instead of a row of its own.
     */
    public static List<FleetNudge> machinesDown(FleetSignals s) {
        return byName(s.machines()).stream()
            .filter(m -> m.isDown(s.lanReachability()))
            .map(m -> FleetNudge.builder().kind(Kind.MACHINE_DOWN)
                .title(m.name() + " is not answering")
                .evidence(lastHeard(m, s) + (lastRun(m, s).filter(BackupRun::isFailure).isPresent()
                    ? " · its last backup failed too" : ""))
                .action("Check it has power and is on its network")
                .value(m.id().value())
                .build())
            .toList();
    }

    /**
     * The backup rows — what went wrong with each enabled job's last run, and the fix Vaier has for it.
     * A run that kept everything, or merely grumbled, says nothing; neither does a machine that is down or
     * switched off on purpose.
     */
    public static List<FleetNudge> backups(FleetSignals s) {
        List<FleetNudge> rows = new ArrayList<>();
        for (Machine m : byName(s.machines())) {
            // Down already says it; switched off on purpose is not expected to back up at all.
            if (m.isDown(s.lanReachability()) || m.isSwitchedOffOnPurpose()) continue;
            Optional<BackupJob> job = jobOf(m, s).filter(BackupJob::enabled);
            Optional<BackupRun> run = lastRun(m, s);
            if (job.isEmpty() || run.isEmpty()) continue;
            backupRow(m, job.get(), run.get()).ifPresent(rows::add);
        }
        return rows;
    }

    private static Optional<FleetNudge> backupRow(Machine m, BackupJob job, BackupRun run) {
        String id = m.id().value();
        if (run.needsClientReadying()) {
            return Optional.of(of(Kind.BACKUP_NEEDS_READYING, m.name() + "'s last backup failed — it has no borg client",
                "Nothing else is wrong; Vaier can install it", "Get this machine ready", id));
        }
        if (run.status() == BackupRunStatus.FAILED) {
            String why = run.summary();
            boolean readable = why != null && !why.isBlank() && why.length() <= EVIDENCE_MAX && !why.contains("\n");
            return Optional.of(of(Kind.BACKUP_FAILED, m.name() + "'s last backup failed",
                readable ? why : "Its backup page says what went wrong", "Open its backup", id));
        }
        if (run.status() != BackupRunStatus.INCOMPLETE) return Optional.empty();
        UnreadableFiles lost = run.unreadableFiles();
        if (MachineNudge.backUpAsRoot(m.name(), Optional.of(run), Optional.of(job)).isPresent()) {
            return Optional.of(of(Kind.BACK_UP_AS_ROOT, m.name() + "'s last backup is missing " + lost.total()
                + (lost.total() == 1 ? " file" : " files"), lost.inOneLine(),
                "Vaier will read every file there, whoever owns them", id));
        }
        return Optional.of(of(Kind.BACKUP_INCOMPLETE, "Files are missing from " + m.name() + "'s last backup",
            lost.any() ? lost.inOneLine() : "Some files could not be read", "Open its backup", id));
    }

    /** DISK_FULL / DISK_FILLING — a disk past, or closing on, the threshold it is judged against. */
    public static List<FleetNudge> disks(FleetSignals s) {
        List<FleetNudge> rows = new ArrayList<>();
        for (Machine m : byName(s.machines())) {
            Optional<MachineDiskStanding> standing = s.diskStandings().stream()
                .filter(d -> m.id().isSameAs(d.machineId())).findFirst();
            if (standing.isEmpty()) continue;
            MachineDiskStanding d = standing.get();
            String title = m.name() + "'s disk is " + d.worstUsedPercent() + "% full";
            if (d.level() == DiskStandingLevel.BREACHING) {
                String more = d.breachingFilesystems() > 1
                    ? " (" + d.breachingFilesystems() + " of its " + d.watchedFilesystems() + " watched are)" : "";
                String risk = m.id().isSameAs(s.vaierServer()) ? " — Vaier itself may stop working" : "";
                rows.add(of(Kind.DISK_FULL, title, d.worstMountPoint() + " is past its " + d.worstThresholdPercent()
                    + "% threshold" + more + risk, "Open its disk", m.id().value()));
            } else if (d.level() == DiskStandingLevel.CLOSING) {
                rows.add(of(Kind.DISK_FILLING, title, d.worstMountPoint() + " is closing on its "
                    + d.worstThresholdPercent() + "% threshold", "Open its disk", m.id().value()));
            }
        }
        rows.sort(Comparator.comparing(FleetNudge::kind));
        return rows;
    }

    /** IMAGE_UPDATES — per machine, the containers whose image has a newer version being served. */
    public static List<FleetNudge> imageUpdates(FleetSignals s) {
        List<FleetNudge> rows = new ArrayList<>();
        for (Machine m : byName(s.machines())) {
            List<String> stale = s.containers().getOrDefault(m.id().value(), List.of()).stream()
                .filter(c -> c.updateAvailable().isUpdateAvailable())
                .map(DockerService::containerName)
                .toList();
            if (stale.isEmpty()) continue;
            rows.add(of(Kind.IMAGE_UPDATES, stale.size() == 1
                    ? "1 container on " + m.name() + " has a newer image"
                    : stale.size() + " containers on " + m.name() + " have a newer image",
                String.join(", ", stale), "See them", m.id().value()));
        }
        return rows;
    }

    private static List<Machine> byName(List<Machine> machines) {
        return machines.stream().sorted(Comparator.comparing(Machine::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private static Optional<BackupJob> jobOf(Machine m, FleetSignals s) {
        return s.backupJobs().stream().filter(j -> m.id().isSameAs(j.machineId())).findFirst();
    }

    private static Optional<BackupRun> lastRun(Machine m, FleetSignals s) {
        if (jobOf(m, s).filter(BackupJob::enabled).isEmpty()) return Optional.empty();
        return s.latestRuns().stream().filter(r -> m.id().isSameAs(r.machineId())).findFirst();
    }

    private static String lastHeard(Machine m, FleetSignals s) {
        if (m.type() == MachineType.LAN_SERVER) {
            Long seen = s.lanLastSeen().get(m.lanAddress());
            return seen == null ? "Has not answered since Vaier started" : "Last answered " + when(seen, s);
        }
        long handshake = handshakeEpoch(m.latestHandshake());
        return handshake <= 0 ? "Has never connected" : "Last heard from " + when(handshake, s);
    }

    private static long handshakeEpoch(String latestHandshake) {
        try {
            return latestHandshake == null ? 0 : Long.parseLong(latestHandshake.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String when(long epochSecond, FleetSignals s) {
        return WHEN.format(Instant.ofEpochSecond(epochSecond).atZone(s.zone()));
    }
}
