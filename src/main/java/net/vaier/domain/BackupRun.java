package net.vaier.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One execution of a {@link BackupJob} and its outcome — the "Backup run" of the fleet-backup feature.
 * It records which job/repository/machine ran, its {@link BackupRunStatus}, the start/finish instants,
 * borg's exit code (null until known), the archive it wrote and a short summary.
 *
 * <p>The mapping from a borg exit code to a status is a business decision and lives here on the entity,
 * following borg's own exit-code contract: {@link #fromExitCode} treats {@code 0} as {@code SUCCESS} and any
 * code {@code >= 2} as {@code FAILED}. Exit {@code 1} splits in two, and the split is the point: borg reports
 * "the archive was written but something went wrong" the same way whether it merely grumbled or whether it was
 * <b>denied on files the job protects</b>. Only the run's own output can tell those apart, so
 * {@link #statusFor} reads it: denied files make the run {@link BackupRunStatus#INCOMPLETE} — an archive with
 * holes in it, which is a failure — and anything else stays {@link BackupRunStatus#WARNING}. {@link #started}
 * opens a run in {@code RUNNING} before an outcome exists, and {@link #failed} records a guard failure that
 * never reached borg at all.
 *
 * @param runId          a unique id for this run
 * @param exitCode       borg's exit code, or null while running / when a guard stopped the run
 * @param archiveName    the archive-name expression the run created under (borg expands it host-side)
 * @param summary        a short human-readable outcome note (never contains the passphrase)
 */
public record BackupRun(
    String runId,
    String jobName,
    String repositoryName,
    String machineName,
    BackupRunStatus status,
    Instant startedAt,
    Instant finishedAt,
    Integer exitCode,
    String archiveName,
    String summary
) {

    public BackupRun {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Backup run runId must not be blank");
        }
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("Backup run jobName must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("Backup run status must not be null");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("Backup run startedAt must not be null");
        }
    }

    /** Open a run for {@code job} in {@code RUNNING}, before any outcome exists. */
    public static BackupRun started(BackupJob job, String runId, Instant now) {
        return new BackupRun(runId, job.name(), job.repositoryName(), job.machineName(),
            BackupRunStatus.RUNNING, now, null, null, job.archiveNameTemplate(), null);
    }

    /**
     * Close a run for {@code job} with borg's {@code exitCode} and its captured {@code summary}, per
     * {@link #statusFor}. This exit-code-and-output-to-status rule is the entity's decision.
     */
    public static BackupRun fromExitCode(BackupJob job, String runId, Instant startedAt, Instant now,
                                         int exitCode, String summary) {
        return new BackupRun(runId, job.name(), job.repositoryName(), job.machineName(),
            statusFor(exitCode, summary), startedAt, now, exitCode, job.archiveNameTemplate(), summary);
    }

    /**
     * Borg's exit-code contract as the entity's rule, refined by what the run actually said:
     * <ul>
     *   <li>{@code 0} — {@code SUCCESS}. borg cannot exit clean having skipped a source file, so the output
     *       is not consulted: a stray line in the captured tail must never demote a clean run.</li>
     *   <li>{@code 1} — the archive was written, but borg was unhappy. If its output names files it could not
     *       read ({@link UnreadableFiles}), those files are missing from the archive and the run is
     *       {@code INCOMPLETE} — a failure. Otherwise (a file changed mid-read, and borg's other benign
     *       grumbles) it is {@code WARNING}, exactly as before.</li>
     *   <li>{@code >= 2} — {@code FAILED}: borg gave up and there is no usable archive from this run. That is
     *       a different problem from an incomplete one (no connection, no repository, a locked repo) and keeps
     *       its own outcome and its own wording.</li>
     * </ul>
     */
    private static BackupRunStatus statusFor(int exitCode, String summary) {
        if (exitCode == 0) {
            return BackupRunStatus.SUCCESS;
        }
        if (exitCode == 1) {
            return UnreadableFiles.from(summary).any()
                ? BackupRunStatus.INCOMPLETE : BackupRunStatus.WARNING;
        }
        return BackupRunStatus.FAILED;
    }

    /**
     * A run for {@code job} that never reached borg because a precondition failed (no machine, SSH
     * access off, or no stored credential). It is {@code FAILED} with no exit code and the reason as
     * its summary.
     */
    public static BackupRun failed(BackupJob job, String runId, Instant now, String reason) {
        return new BackupRun(runId, job.name(), job.repositoryName(), job.machineName(),
            BackupRunStatus.FAILED, now, now, null, null, reason);
    }

    /**
     * The opening of the one refusal an operator can act on without leaving the page. Kept as a constant
     * because two things depend on it agreeing with itself: the run that is written and
     * {@link #needsClientReadying()}, which reads it back.
     */
    private static final String BORG_MISSING = "borg is not installed on ";

    /**
     * A run refused before borg because the machine has no borg client yet. This failure is unlike the
     * others: nothing is broken — the host is reachable, the credential works, the repository is fine — the
     * client simply was never installed, and Vaier can install it (see {@code PrepareBackupClientUseCase}).
     *
     * <p>It used to say "run Prepare client", naming a button on the Backups page, which was deleted when
     * the Explorer absorbed it. A message pointing at a control that no longer exists is worse than no
     * message: it tells the operator a fix exists and makes finding it their problem. So it names where they
     * already are — the machine's Backup entry — which is also where {@link #needsClientReadying()} puts the
     * action that does it for them.
     */
    public static BackupRun borgMissing(BackupJob job, String runId, Instant now) {
        return failed(job, runId, now, BORG_MISSING + job.machineName()
            + " — nothing else is wrong. Open this machine's Backup entry and choose "
            + "“Get this machine ready”, and Vaier will install it.");
    }

    /**
     * Whether this run failed only because its machine has no borg client — the one outcome that a single
     * action fixes. Deciding this is the domain's job and not the browser's: the alternative is a UI that
     * pattern-matches an error string, which puts a business rule in a view and breaks silently the day the
     * wording changes.
     */
    public boolean needsClientReadying() {
        return status == BackupRunStatus.FAILED && summary != null && summary.startsWith(BORG_MISSING);
    }

    /**
     * Settle this (typically {@code RUNNING}) run with the detached borg chain's {@code exitCode} and its
     * captured output — the same {@link #statusFor} rule as {@link #fromExitCode} — preserving the run's
     * identity (id, job, repository, machine, archive, start time) and stamping the finish instant, exit code
     * and {@code summary}. This is the wither a poll uses to promote an in-flight run to its outcome.
     */
    public BackupRun completedFrom(int exitCode, Instant now, String summary) {
        return new BackupRun(runId, jobName, repositoryName, machineName, statusFor(exitCode, summary),
            startedAt, now, exitCode, archiveName, summary);
    }

    /**
     * Move this run to {@code UNKNOWN}: a run that was {@code RUNNING} but can no longer be resolved —
     * the poll succeeds yet no result file exists after the grace window, so the outcome is genuinely
     * unknowable rather than a success or failure. Preserves the run's identity and stamps the finish
     * instant and {@code summary}.
     */
    public BackupRun asUnknown(Instant now, String summary) {
        return new BackupRun(runId, jobName, repositoryName, machineName, BackupRunStatus.UNKNOWN,
            startedAt, now, null, archiveName, summary);
    }

    /**
     * Whether this run is still {@code RUNNING} and has been so longer than {@code grace} — the signal a
     * poller uses to stop waiting on an un-resolvable in-flight run and move it to {@code UNKNOWN}. A run
     * that has already settled is never stale-while-running.
     */
    public boolean isStaleWhileRunning(Instant now, Duration grace) {
        return status == BackupRunStatus.RUNNING
            && Duration.between(startedAt, now).compareTo(grace) > 0;
    }

    /** Whether this run did not complete successfully. */
    public boolean isFailure() {
        return status.isFailure();
    }

    /**
     * The source files this run could not read, read back out of its own {@code summary} — which files are
     * missing from the archive, and how many.
     *
     * <p>Derived rather than stored, deliberately: {@code summary} <em>is</em> the run's captured borg output
     * and is already bounded when it is captured (a fixed-size tail), so parsing it on demand keeps the
     * unbounded list of denied paths out of the run store entirely while staying exact about what that output
     * holds. {@link UnreadableFiles} then caps what it hands on for display. Never null; empty on a clean run.
     */
    public UnreadableFiles unreadableFiles() {
        return UnreadableFiles.from(summary);
    }

    /**
     * The run diagnostics: the human-readable lines of {@code summary} — the skipped-file and error lines a
     * person needs in order to act — with borg's machine-readable JSON stats object removed. Deciding what
     * in a raw borg summary is worth showing a human is this entity's call, not a view's.
     *
     * <p>borg pretty-prints its {@code --json --stats} object as a block whose braces sit alone at column 0,
     * so the block is identified structurally: it opens at the first line that is exactly <code>{</code> and
     * closes at the first following line that is exactly <code>}</code>. Everything outside that block is
     * kept — including borg prune's "Keeping archive (rule: …)" report, which follows the object on a clean
     * run. A summary with no such block (e.g. {@code sh: 1: borg: not found}) is diagnostics in its entirety;
     * an unterminated block is treated as running to the end. The method is total: it never throws and never
     * returns null, and — like {@code summary} itself — it never carries the passphrase.
     *
     * @return the diagnostic lines, or an empty string when there are none (a clean run says nothing)
     */
    public String diagnostics() {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        List<String> lines = summary.lines().toList();
        int open = lineThatIsExactly(lines, "{", 0);
        if (open < 0) {
            return summary.strip();          // no stats object at all — it is all diagnostics
        }
        int close = lineThatIsExactly(lines, "}", open + 1);
        int lastOfBlock = close < 0 ? lines.size() - 1 : close;   // unterminated block runs to the end

        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i >= open && i <= lastOfBlock) {
                continue;
            }
            kept.append(lines.get(i)).append('\n');
        }
        return kept.toString().strip();
    }

    /** Index of the first line at or after {@code from} that consists of exactly {@code token}, else -1. */
    private static int lineThatIsExactly(List<String> lines, String token, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (token.equals(lines.get(i).stripTrailing())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Subject line for the admin failure alert, sent once when a job crosses from healthy to failing. An
     * {@code INCOMPLETE} run says so rather than "failed": in an inbox, "failed" reads as "the backup did not
     * run" and would be dismissed as noise on a job that visibly runs every night — whereas "incomplete" is
     * the one word that makes an operator open it. Which word to use is the entity's call, not the mailer's.
     */
    public String failureSubject() {
        String what = status == BackupRunStatus.INCOMPLETE ? "Backup incomplete: " : "Backup failed: ";
        return "[Vaier] " + what + jobName + " on " + machineName;
    }

    /** Subject line for the admin all-clear, sent once when a previously failing job succeeds again. */
    public String recoverySubject() {
        return "[Vaier] Backup recovered: " + jobName + " on " + machineName;
    }

    /**
     * Body for the admin failure / recovery email — job, machine, repository, exit code, finish instant
     * and the {@code summary} tail (never the passphrase), ending with a link to the Vaier UI built from
     * {@code baseDomain} (omitted when it is null or blank). Rendering lives here on the entity, mirroring
     * {@link RemoteDiskUsage#pressureBody}; the notification service only sequences the SMTP send.
     */
    public String failureBody(String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append("Backup job: ").append(jobName).append("\n");
        body.append("Machine: ").append(machineName).append("\n");
        body.append("Repository: ").append(repositoryName).append("\n");
        body.append("Status: ").append(status).append("\n");
        body.append("Exit code: ").append(exitCode != null ? exitCode : "unknown").append("\n");
        body.append("Finished: ").append(finishedAt != null ? finishedAt : "unknown").append("\n");
        // What is missing goes ABOVE the raw borg tail, because that is the whole message: an operator
        // reading this must not have to find the denial lines in borg's output to learn they lost data —
        // which is exactly how the Colina 27 holes went unnoticed for months.
        String missing = unreadableFiles().report();
        if (!missing.isEmpty()) {
            body.append("\n").append(missing);
        }
        if (summary != null && !summary.isBlank()) {
            body.append("\n").append(summary.strip()).append("\n");
        }
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/\n");
        }
        return body.toString();
    }

    /** Body for the all-clear email; same shape as {@link #failureBody}, reused as {@code RemoteDiskUsage} does. */
    public String recoveryBody(String baseDomain) {
        return failureBody(baseDomain);
    }
}
