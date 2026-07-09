package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.ListArchivesUseCase;
import net.vaier.application.NotifyAdminsOfBackupFailureUseCase;
import net.vaier.application.RunBackupJobUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.Archive;
import net.vaier.domain.BackupFailureTracker;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupRunStatus;
import net.vaier.domain.BorgCommand;
import net.vaier.domain.CommandResult;
import net.vaier.domain.Machine;
import net.vaier.domain.port.ForRecordingBackupRuns;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Fleet-backup orchestrator: runs one {@link BackupJob} over SSH and records the resulting
 * {@link BackupRun}. It lives in {@code rest/} and fans several narrow {@code *UseCase}s together —
 * exactly as {@link RemoteDiskWatcher} does — because that fan-out is a web-layer concern, not a
 * service one. It never touches the SSH ports directly: every borg command goes through
 * {@link RunRemoteCommandUseCase}, which resolves the machine, authenticates from the vault and pins
 * the host key.
 *
 * <p>It applies the same guards as the disk watcher before running anything: an unknown machine, one
 * with SSH access turned off, or one Vaier holds no credential for is never contacted — the run is
 * recorded as {@code FAILED} with a clear reason rather than mounting a failed-auth attempt. A
 * non-zero borg exit is a normal result and maps to a {@code FAILED} run; only genuinely transient
 * SSH errors are swallowed (as a {@code FAILED} run) so a single unreachable host cannot throw.
 *
 * <p>Only the {@link BorgCommand.BuiltCommand#redacted() redacted} command is ever logged, and every
 * user-supplied name passes through {@link LogSafe#forLog} first, so neither the passphrase nor a
 * forged log line can escape.
 *
 * <p>Because a multi-GB borg run blows past {@code MinaSshSessionAdapter}'s 20 s exec cap,
 * {@link #runJob} does not wait for borg: it sends the {@link BorgCommand#detachedRun detached}
 * command, which {@code nohup}s borg and returns {@code STARTED <pid>} at once, and records the run as
 * {@code RUNNING}. A scheduled {@link #pollRunningRuns()} then reads each in-flight run's result file
 * over the normal exec path and promotes it to {@code SUCCESS}/{@code FAILED}, or — once it is
 * un-resolvable past the grace window — {@code UNKNOWN}. Because the RUNNING state lives in the run
 * store (and the {@code .rc} file lives on the host), a Vaier restart re-adopts in-flight runs on the
 * next poll with no in-memory state to lose.
 */
@Component
@Slf4j
public class BackupRunner implements RunBackupJobUseCase, ListArchivesUseCase {

    /** How often the scheduler sweeps in-flight RUNNING runs for a result. */
    static final long POLL_INTERVAL_MS = 60_000;

    /**
     * How often the scheduler sweeps for due jobs. It runs frequently (every 15 min) but only <em>fires</em>
     * when the current hour equals the configured schedule hour, so it behaves like a nightly run at hour H
     * without any cron expression or on-host scheduler. Re-reading the hour each tick keeps it live-reconfigurable.
     */
    static final long DUE_SCAN_INTERVAL_MS = 900_000;

    /**
     * How long a run may stay {@code RUNNING} with the poll succeeding but reporting no result before it
     * is declared {@code UNKNOWN}. Generous enough to cover a legitimately long multi-hour backup.
     */
    private static final Duration RUN_GRACE = Duration.ofHours(12);

    private final GetMachinesUseCase machines;
    private final GetHostCredentialUseCase credentials;
    private final RunRemoteCommandUseCase remoteCommand;
    private final ForRecordingBackupRuns runs;
    private final GetBackupRepositoriesUseCase repositories;
    private final GetBackupJobsUseCase jobs;
    private final NotifyAdminsOfBackupFailureUseCase backupNotifier;
    private final ConfigResolver configResolver;
    private final BackupWorkDirResolver workDirResolver;
    private final Clock clock;

    /**
     * Per-job failure-transition state so admins are alerted only when a job crosses from healthy to
     * failing (and get a single all-clear on recovery), not every failing night. In-memory like the disk
     * watcher's tracker; a restart never re-alerts already-terminal runs because only a run that settles
     * while RUNNING this tick feeds it.
     */
    private final BackupFailureTracker failureTracker = new BackupFailureTracker();

    public BackupRunner(GetMachinesUseCase machines,
                        GetHostCredentialUseCase credentials,
                        RunRemoteCommandUseCase remoteCommand,
                        ForRecordingBackupRuns runs,
                        GetBackupRepositoriesUseCase repositories,
                        GetBackupJobsUseCase jobs,
                        NotifyAdminsOfBackupFailureUseCase backupNotifier,
                        ConfigResolver configResolver,
                        Clock clock,
                        BackupWorkDirResolver workDirResolver) {
        this.machines = machines;
        this.credentials = credentials;
        this.remoteCommand = remoteCommand;
        this.runs = runs;
        this.repositories = repositories;
        this.jobs = jobs;
        this.backupNotifier = backupNotifier;
        this.configResolver = configResolver;
        this.clock = clock;
        this.workDirResolver = workDirResolver;
    }

    /**
     * The {@link RunBackupJobUseCase} seam the controller triggers a run through: it generates the run id
     * itself — deterministically from {@code job} and the injected {@link Clock} — so the controller never
     * has to, then delegates to {@link #runJob(BackupJob, BackupRepository, String)}.
     */
    @Override
    public BackupRun runJob(BackupJob job, BackupRepository repo) {
        return runJob(job, repo, job.runId(clock.millis()));
    }

    /**
     * Launch {@code job} against {@code repo} over SSH and record it as {@code RUNNING}, returning at
     * once — borg is detached with {@code nohup} and resolved later by {@link #pollRunningRuns()}. Guards
     * (unknown machine / SSH off / no credential) short-circuit to a recorded {@code FAILED} run without
     * contacting the host. A launch that never confirms with {@code STARTED} (a non-zero exit, a timeout,
     * or an SSH error) is recorded {@code FAILED} rather than left as a phantom RUNNING run.
     */
    public BackupRun runJob(BackupJob job, BackupRepository repo, String runId) {
        Optional<Machine> machine = findMachine(job.machineName());
        if (machine.isEmpty()) {
            return recorded(BackupRun.failed(job, runId, clock.instant(),
                "No machine named " + job.machineName()));
        }
        if (!machine.get().effectiveSshAccess()) {
            return recorded(BackupRun.failed(job, runId, clock.instant(),
                "SSH access is disabled for " + job.machineName()));
        }
        if (credentials.getHostCredential(machine.get().name()).isEmpty()) {
            return recorded(BackupRun.failed(job, runId, clock.instant(),
                "No stored credential for " + job.machineName()));
        }

        // The run reads the passphrase from a provisioned 0600 file via BORG_PASSCOMMAND, so make sure that
        // file exists before launching (write-if-absent) — a run must never fail merely because the pass
        // file was never provisioned. Best-effort: if this cannot run, the launch still proceeds and a
        // genuine auth failure settles the run FAILED on poll.
        String workDir = workDirResolver.workDirFor(machine.get().name());
        ensurePassFile(machine.get().name(), repo, workDir);

        BorgCommand.BuiltCommand command = BorgCommand.detachedRun(job, repo, runId, workDir);
        log.info("Launching backup job {} on {}: {}",
            LogSafe.forLog(job.name()), LogSafe.forLog(machine.get().name()), command.redacted());
        Instant startedAt = clock.instant();
        try {
            CommandResult result = remoteCommand.run(machine.get().name(), command.exec());
            if (result.timedOut() || result.exitCode() != 0
                || result.stdout() == null || !result.stdout().contains("STARTED")) {
                return recorded(BackupRun.failed(job, runId, startedAt,
                    "Backup failed to launch: " + summaryOf(result)));
            }
            return recorded(BackupRun.started(job, runId, startedAt));
        } catch (Exception e) {
            log.debug("Backup job {} on {} failed to launch: {}",
                LogSafe.forLog(job.name()), LogSafe.forLog(machine.get().name()), e.getMessage());
            return recorded(BackupRun.failed(job, runId, startedAt,
                "Backup command failed: " + e.getMessage()));
        }
    }

    /**
     * Vaier-owned nightly scheduling: a frequent sweep that only <em>fires</em> at the configured schedule
     * hour, so due jobs run once a night without any cron expression or on-host scheduler (the "Vaier owns
     * scheduling" decision). The hour and today are both derived from the injected {@link Clock} in its zone,
     * and the hour is re-read from {@link ConfigResolver} each tick so a change takes effect without a restart.
     *
     * <p>Off the schedule hour it does nothing. On it, it loops the configured jobs and asks each
     * {@link BackupJob#isDue} — the job's own decision — passing the last recorded run; a due job's repository
     * is resolved and the run launched through the same {@link #runJob(BackupJob, BackupRepository)} path as an
     * on-demand run (so the identical machine/SSH/credential guards apply). Because {@code isDue} returns false
     * once any run is dated today (a {@code RUNNING} launch included), a second tick within the same hour never
     * re-fires a job that already started. A missing repository or a transient per-job error is swallowed at
     * {@code debug} so one bad job can never stall the sweep.
     */
    @Scheduled(fixedDelay = DUE_SCAN_INTERVAL_MS)
    public void runDueJobs() {
        if (clock.instant().atZone(clock.getZone()).getHour() != configResolver.getBackupScheduleHour()) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        for (BackupJob job : jobs.getBackupJobs()) {
            try {
                if (!job.isDue(today, clock.getZone(), runs.latestForJob(job.name()))) {
                    continue;
                }
                Optional<BackupRepository> repo = repositories.getBackupRepositories().stream()
                    .filter(r -> r.name().equals(job.repositoryName())).findFirst();
                if (repo.isEmpty()) {
                    log.debug("Skipping due backup job {}: its repository {} is not configured",
                        LogSafe.forLog(job.name()), LogSafe.forLog(job.repositoryName()));
                    continue;
                }
                log.info("Nightly schedule firing due backup job {}", LogSafe.forLog(job.name()));
                runJob(job, repo.get());
            } catch (Exception e) {
                log.debug("Scheduled backup of job {} failed transiently: {}",
                    LogSafe.forLog(job.name()), e.getMessage());
            }
        }
    }

    /**
     * List the archives held in the repository named {@code repositoryName}. Because {@code borg list}
     * runs on a client host (a repository alone has no machine), this resolves the repository, then picks
     * a machine from a job that targets it — a first enabled job, falling back to any job — and runs the
     * (fast, non-detached) list over SSH. Applies the same guards as {@link #runJob} (SSH access on, a
     * stored credential) and returns an empty list — never throwing — when the repository is unknown, no
     * job references it (no host to list from), the host is unreachable, or {@code borg list} fails.
     */
    @Override
    public List<Archive> listArchives(String repositoryName) {
        Optional<BackupRepository> repo = repositories.getBackupRepositories().stream()
            .filter(r -> r.name().equals(repositoryName)).findFirst();
        if (repo.isEmpty()) {
            return List.of();
        }
        Optional<BackupJob> job = firstJobTargeting(repositoryName);
        if (job.isEmpty()) {
            log.debug("Cannot list archives for repository {}: no job targets it, so no host to list from",
                LogSafe.forLog(repositoryName));
            return List.of();
        }
        Optional<Machine> machine = findMachine(job.get().machineName());
        if (machine.isEmpty() || !machine.get().effectiveSshAccess()
            || credentials.getHostCredential(machine.get().name()).isEmpty()) {
            log.debug("Cannot list archives for repository {}: machine or credential unavailable",
                LogSafe.forLog(repositoryName));
            return List.of();
        }
        // borg list unlocks the repo via BORG_PASSCOMMAND too, so ensure the pass file is provisioned first.
        String workDir = workDirResolver.workDirFor(machine.get().name());
        ensurePassFile(machine.get().name(), repo.get(), workDir);
        BorgCommand.BuiltCommand command = BorgCommand.listArchives(repo.get(), workDir);
        log.info("Listing archives for repository {} on {}: {}",
            LogSafe.forLog(repositoryName), LogSafe.forLog(machine.get().name()), command.redacted());
        try {
            CommandResult result = remoteCommand.run(machine.get().name(), command.exec());
            if (result.timedOut() || result.exitCode() != 0) {
                log.debug("borg list for repository {} on {} failed (exit={}, timedOut={})",
                    LogSafe.forLog(repositoryName), LogSafe.forLog(machine.get().name()),
                    result.exitCode(), result.timedOut());
                return List.of();
            }
            return Archive.parseList(result.stdout());
        } catch (Exception e) {
            log.debug("Listing archives for repository {} failed transiently: {}",
                LogSafe.forLog(repositoryName), e.getMessage());
            return List.of();
        }
    }

    /** A machine to list from: a first enabled job targeting the repo, else any job that targets it. */
    private Optional<BackupJob> firstJobTargeting(String repositoryName) {
        List<BackupJob> all = jobs.getBackupJobs();
        return all.stream()
            .filter(j -> j.repositoryName().equals(repositoryName) && j.enabled())
            .findFirst()
            .or(() -> all.stream().filter(j -> j.repositoryName().equals(repositoryName)).findFirst());
    }

    /**
     * Sweep every {@code RUNNING} run in the store and try to settle it. For each, resolve its machine
     * and the same guards as {@link #runJob}, read the run's result file over SSH, and — if the run has
     * finished — promote it to {@code SUCCESS}/{@code FAILED} with a log-tail summary. A poll that
     * succeeds but still reports RUNNING past {@link #RUN_GRACE} with no result file settles the run to
     * {@code UNKNOWN}. Any transient failure (guard temporarily unmet, SSH error, poll timeout/non-zero)
     * is swallowed at {@code debug} and the run is left RUNNING to retry next tick — a blip must never
     * masquerade as a failed backup. Reads the run store fresh each tick, so a restarted Vaier re-adopts
     * in-flight runs automatically.
     */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void pollRunningRuns() {
        for (BackupRun run : runs.getAll()) {
            if (run.status() == BackupRunStatus.RUNNING) {
                pollRun(run);
            }
        }
    }

    private void pollRun(BackupRun run) {
        try {
            Optional<Machine> machine = findMachine(run.machineName());
            if (machine.isEmpty() || !machine.get().effectiveSshAccess()
                || credentials.getHostCredential(machine.get().name()).isEmpty()) {
                log.debug("Cannot poll backup {} for job {}: machine or credential unavailable; leaving RUNNING",
                    LogSafe.forLog(run.runId()), LogSafe.forLog(run.jobName()));
                return;
            }
            String workDir = workDirResolver.workDirFor(run.machineName());
            CommandResult poll = remoteCommand.run(machine.get().name(),
                BorgCommand.pollStatus(run.runId(), workDir));
            if (poll.timedOut() || poll.exitCode() != 0) {
                log.debug("Poll of backup {} on {} failed (exit={}, timedOut={}); leaving RUNNING",
                    LogSafe.forLog(run.runId()), LogSafe.forLog(machine.get().name()),
                    poll.exitCode(), poll.timedOut());
                return;
            }
            Optional<Integer> exitCode = BorgCommand.parsePoll(poll.stdout());
            if (exitCode.isPresent()) {
                String summary = fetchSummary(machine.get().name(), run.runId(), exitCode.get(), workDir);
                BackupRun terminal = run.completedFrom(exitCode.get(), clock.instant(), summary);
                recorded(terminal);
                log.info("Backup {} for job {} finished with exit {}",
                    LogSafe.forLog(run.runId()), LogSafe.forLog(run.jobName()), exitCode.get());
                alertOnTransition(terminal);
            } else if (run.isStaleWhileRunning(clock.instant(), RUN_GRACE)) {
                // UNKNOWN is an indeterminate outcome, not a failure for alerting: settle the run but never
                // page or all-clear on it, and leave the failure tracker untouched. Logged as a warning.
                recorded(run.asUnknown(clock.instant(),
                    "Backup still running after grace period with no result file"));
                log.warn("Backup {} for job {} unresolved past grace window; recorded UNKNOWN (no alert)",
                    LogSafe.forLog(run.runId()), LogSafe.forLog(run.jobName()));
            }
        } catch (Exception e) {
            log.debug("Poll of backup {} failed transiently: {}",
                LogSafe.forLog(run.runId()), e.getMessage());
        }
    }

    /**
     * Feed a freshly-settled terminal run (SUCCESS/FAILED only — never UNKNOWN) to the per-job failure
     * tracker and alert admins only on a boundary crossing: once when a job goes healthy→failing, and a
     * single all-clear when it recovers failing→healthy. Steady nightly failures produce NONE and stay
     * quiet. A notification failure is swallowed so it can never break the poll sweep.
     */
    private void alertOnTransition(BackupRun terminal) {
        try {
            switch (failureTracker.update(terminal.jobName(), terminal.isFailure())) {
                case CROSSED_TO_FAILING -> backupNotifier.notifyAdminsOfBackupFailure(terminal);
                case CROSSED_TO_HEALTHY -> backupNotifier.notifyAdminsOfBackupRecovery(terminal);
                case NONE -> { /* no boundary crossed; stay quiet */ }
            }
        } catch (Exception e) {
            log.warn("Failed to alert admins of backup transition for job {}: {}",
                LogSafe.forLog(terminal.jobName()), e.getMessage());
        }
    }

    /** The tail of the run's on-host log for the summary, or a generic note if it cannot be read. */
    private String fetchSummary(String machineName, String runId, int exitCode, String workDir) {
        try {
            CommandResult logTail = remoteCommand.run(machineName, BorgCommand.fetchLog(runId, workDir));
            if (!logTail.timedOut() && logTail.exitCode() == 0
                && logTail.stdout() != null && !logTail.stdout().isBlank()) {
                return logTail.stdout().strip();
            }
        } catch (Exception e) {
            log.debug("Could not fetch log tail for backup {}: {}", LogSafe.forLog(runId), e.getMessage());
        }
        return exitCode == 0 ? "Backup completed" : "Backup failed with exit " + exitCode;
    }

    private Optional<Machine> findMachine(String name) {
        return machines.getAllMachines().stream()
            .filter(m -> m.name().equals(name))
            .findFirst();
    }

    /**
     * Write-if-absent the repository's passphrase file on {@code machineName} so borg can read it via
     * {@code BORG_PASSCOMMAND}. Best-effort and never throwing: any failure is logged at {@code debug} and
     * the caller proceeds — a genuinely missing secret surfaces as a normal FAILED run on poll, never as an
     * exception. Only the {@link BorgCommand.BuiltCommand#redacted() redacted} form is logged so the
     * plaintext never reaches the log.
     */
    private void ensurePassFile(String machineName, BackupRepository repo, String workDir) {
        try {
            BorgCommand.BuiltCommand ensure = BorgCommand.ensurePassFile(repo, workDir);
            log.debug("Ensuring backup passphrase file for repository {} on {}: {}",
                LogSafe.forLog(repo.name()), LogSafe.forLog(machineName), ensure.redacted());
            remoteCommand.run(machineName, ensure.exec());
        } catch (Exception e) {
            log.debug("Could not ensure passphrase file for repository {} on {}: {}",
                LogSafe.forLog(repo.name()), LogSafe.forLog(machineName), e.getMessage());
        }
    }

    private BackupRun recorded(BackupRun run) {
        runs.record(run);
        return run;
    }

    private static String summaryOf(CommandResult result) {
        if (result.exitCode() == 0) {
            return "Backup completed";
        }
        String stderr = result.stderr();
        return stderr != null && !stderr.isBlank() ? stderr : "Backup failed with exit " + result.exitCode();
    }
}
