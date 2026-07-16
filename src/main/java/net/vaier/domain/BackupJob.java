package net.vaier.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * A per-host fleet-backup specification: which machine's data to back up, into which
 * {@link BackupRepository}, from which source paths, with which excludes, compression and retention.
 * One job maps to one host's borg {@code create}; its {@link #archiveGlob()} scopes prune/list to just
 * this job's archives in a repository shared by the whole fleet.
 *
 * <p>The job owns the archive-naming convention: {@link #archiveNameTemplate()} is the borg placeholder
 * expression borg expands at create time (per-host hostname + ISO timestamp), and {@link #archiveGlob()}
 * matches every archive this job has ever written.
 *
 * <p><b>Back up as root.</b> Vaier runs borg over SSH as the machine's credential user (e.g. {@code ubuntu}),
 * never root — so every file in a source path that user cannot read is <em>silently skipped</em>: borg exits
 * 1, the run settles WARNING, and the archive has holes. Container volumes are the usual victims (a
 * mosquitto {@code .db} owned {@code 1883:1883} mode {@code 0600}, a pihole file owned {@code root:root}).
 * Per-file chmod is whack-a-mole — every new volume is a fresh silent hole — so a job may opt in to
 * {@link #backupAsRoot()}, and its borg then runs under {@code sudo -n} on the machine (what that changes on
 * the command line is {@link BorgCommand}'s decision; the sudoers grant that permits it is installed by
 * {@link BorgClientSetupScript}). It is <b>opt-in</b>: a job never escalates to root on its own.
 *
 * @param sourcePaths the paths on the machine to back up (at least one)
 * @param excludes    borg {@code --exclude} patterns (may be empty)
 * @param keepDaily   daily archives to keep on prune (≥ 0)
 * @param keepWeekly  weekly archives to keep on prune (≥ 0)
 * @param keepMonthly monthly archives to keep on prune (≥ 0)
 * @param compression  the borg {@code --compression} spec; defaults to {@code zstd,6} when blank
 * @param enabled      whether nightly scheduling should run this job
 * @param backupAsRoot whether this job's borg runs as root on the machine (see below)
 */
public record BackupJob(
    String name,
    String machineName,
    String repositoryName,
    List<String> sourcePaths,
    List<String> excludes,
    int keepDaily,
    int keepWeekly,
    int keepMonthly,
    String compression,
    boolean enabled,
    boolean backupAsRoot
) {

    /** The borg compression used when none is configured. */
    public static final String DEFAULT_COMPRESSION = "zstd,6";

    public BackupJob {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Backup job name must not be blank");
        }
        if (machineName == null || machineName.isBlank()) {
            throw new IllegalArgumentException("Backup job machineName must not be blank");
        }
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new IllegalArgumentException("Backup job repositoryName must not be blank");
        }
        if (sourcePaths == null || sourcePaths.isEmpty()) {
            throw new IllegalArgumentException("Backup job must have at least one source path");
        }
        if (keepDaily < 0 || keepWeekly < 0 || keepMonthly < 0) {
            throw new IllegalArgumentException("Backup job retention counts must be >= 0");
        }
        if (keepDaily == 0 && keepWeekly == 0 && keepMonthly == 0) {
            throw new IllegalArgumentException("Backup job must keep at least one daily/weekly/monthly archive");
        }
        sourcePaths = List.copyOf(sourcePaths);
        excludes = excludes == null ? List.of() : List.copyOf(excludes);
        compression = (compression == null || compression.isBlank()) ? DEFAULT_COMPRESSION : compression;
    }

    /**
     * A copy of this job protecting {@code newSourcePaths} instead of its current ones. The paths are the
     * job's only mutable-by-selection field in the just-select-and-back-up flow; every other field
     * (repository, retention, compression, flags) is carried through unchanged. The caller supplies an
     * already-normalized, non-empty set (see {@link SourcePaths}) — an empty job means "stop backing up",
     * which deletes the job rather than saving an empty one.
     */
    public BackupJob withSourcePaths(List<String> newSourcePaths) {
        return new BackupJob(name, machineName, repositoryName, newSourcePaths, excludes,
            keepDaily, keepWeekly, keepMonthly, compression, enabled, backupAsRoot);
    }

    /**
     * The borg archive-name placeholder expression this job creates archives under: the machine's own
     * hostname plus an ISO-8601 timestamp, both expanded by borg at create time. Every host's archives
     * are therefore self-identifying within a shared repository.
     */
    public String archiveNameTemplate() {
        return "{hostname}-{now:%Y-%m-%dT%H:%M:%S}";
    }

    /** The glob matching every archive this job has written, used to scope prune/list in a shared repo. */
    public String archiveGlob() {
        return "{hostname}-*";
    }

    /**
     * A unique, filesystem- and shell-safe run id for one execution of this job at {@code epochMillis}.
     * The id becomes part of an on-host path ({@code $W/<runId>.rc}) and a shell word, so any character
     * outside {@code [A-Za-z0-9._-]} in the job name is collapsed to {@code -}. Uniqueness comes from the
     * millisecond timestamp: {@code job-<safe-name>-<epochMillis>}. Deriving a safe id is the job's own
     * decision, so it lives on the entity rather than in the runner.
     */
    public String runId(long epochMillis) {
        String safeName = name.replaceAll("[^A-Za-z0-9._-]", "-");
        return "job-" + safeName + "-" + epochMillis;
    }

    /**
     * Whether {@code repositories} contains the {@link BackupRepository} this job backs up into. A job
     * may only be saved when its {@link #repositoryName()} names a repository that actually exists —
     * that referential rule is the job's own decision, so it lives here on the entity rather than in a
     * service. The orchestrating service supplies the known repositories and acts on the answer.
     */
    public boolean referencesKnownRepository(Collection<BackupRepository> repositories) {
        return repositories.stream().anyMatch(r -> r.name().equals(repositoryName));
    }

    /**
     * Whether Vaier-owned nightly scheduling should fire this job right now — the "should this job run"
     * decision, which is the job's own and so lives here on the entity rather than in the scheduler.
     *
     * <p>A job is due when it is {@link #enabled()} and no run of it is already dated {@code today}. The
     * scheduler passes {@code today} (derived from its clock) and the {@code zone} it was derived in, so
     * {@code lastRun}'s {@link BackupRun#startedAt()} is bucketed to a calendar day in the same zone —
     * keeping the day comparison deterministic and correct across day boundaries.
     *
     * <p>Any run already dated today blocks a second auto-fire: a {@code SUCCESS} means today's backup is
     * done, a {@code RUNNING} run must not be double-fired mid-flight, and a {@code FAILED}/{@code UNKNOWN}
     * attempt today is <em>not</em> auto-retried — a retry is a manual run, and the scheduler only tries
     * again tomorrow. A run dated on a previous day never blocks today's scheduled attempt.
     */
    public boolean isDue(LocalDate today, ZoneId zone, Optional<BackupRun> lastRun) {
        if (!enabled) {
            return false;
        }
        return lastRun
            .map(run -> localDateOf(run.startedAt(), zone))
            .map(runDate -> runDate.isBefore(today))
            .orElse(true);
    }

    private static LocalDate localDateOf(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toLocalDate();
    }
}
