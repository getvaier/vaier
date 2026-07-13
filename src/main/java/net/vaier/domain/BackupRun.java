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
 * following borg's own exit-code contract: {@link #fromExitCode} treats {@code 0} as {@code SUCCESS},
 * {@code 1} as {@code WARNING} (the archive was created but some files were skipped) and any code
 * {@code >= 2} as {@code FAILED}. {@link #started} opens a run in {@code RUNNING} before an outcome
 * exists, and {@link #failed} records a guard failure that never reached borg at all.
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
     * Close a run for {@code job} with borg's {@code exitCode}, following borg's exit-code contract:
     * {@code 0} is {@code SUCCESS}, {@code 1} is {@code WARNING} (archive created, some files skipped)
     * and any code {@code >= 2} is {@code FAILED}. This exit-code-to-status rule is the entity's decision.
     */
    public static BackupRun fromExitCode(BackupJob job, String runId, Instant startedAt, Instant now,
                                         int exitCode, String summary) {
        return new BackupRun(runId, job.name(), job.repositoryName(), job.machineName(),
            statusFor(exitCode), startedAt, now, exitCode, job.archiveNameTemplate(), summary);
    }

    /**
     * Borg's exit-code contract as the entity's rule: {@code 0} is {@code SUCCESS}, {@code 1} is
     * {@code WARNING} (the archive was created but some files were skipped, e.g. unreadable by the SSH
     * user), and any code {@code >= 2} is {@code FAILED}.
     */
    private static BackupRunStatus statusFor(int exitCode) {
        if (exitCode == 0) {
            return BackupRunStatus.SUCCESS;
        }
        if (exitCode == 1) {
            return BackupRunStatus.WARNING;
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
     * Settle this (typically {@code RUNNING}) run with the detached borg chain's {@code exitCode}: the
     * same rule as {@link #fromExitCode} — {@code 0} is {@code SUCCESS}, {@code 1} is {@code WARNING}
     * (archive created, some files skipped) and any code {@code >= 2} is {@code FAILED} — preserving the
     * run's identity (id, job, repository, machine, archive, start time) and stamping the finish instant,
     * exit code and {@code summary}. This is the wither a poll uses to promote an in-flight run to its
     * outcome.
     */
    public BackupRun completedFrom(int exitCode, Instant now, String summary) {
        return new BackupRun(runId, jobName, repositoryName, machineName, statusFor(exitCode),
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

    /** Subject line for the admin failure alert, sent once when a job crosses from healthy to failing. */
    public String failureSubject() {
        return "[Vaier] Backup failed: " + jobName + " on " + machineName;
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
