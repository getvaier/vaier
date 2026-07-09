package net.vaier.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * One execution of a {@link BackupJob} and its outcome — the "Backup run" of the fleet-backup feature.
 * It records which job/repository/machine ran, its {@link BackupRunStatus}, the start/finish instants,
 * borg's exit code (null until known), the archive it wrote and a short summary.
 *
 * <p>The mapping from a borg exit code to a status is a business decision and lives here on the entity:
 * {@link #fromExitCode} treats {@code 0} as {@code SUCCESS} and any non-zero code as {@code FAILED}.
 * {@link #started} opens a run in {@code RUNNING} before an outcome exists, and {@link #failed} records a
 * guard failure that never reached borg at all.
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
     * Close a run for {@code job} with borg's {@code exitCode}: {@code 0} is {@code SUCCESS}, any
     * non-zero code is {@code FAILED}. This exit-code-to-status rule is the entity's decision.
     */
    public static BackupRun fromExitCode(BackupJob job, String runId, Instant startedAt, Instant now,
                                         int exitCode, String summary) {
        BackupRunStatus status = exitCode == 0 ? BackupRunStatus.SUCCESS : BackupRunStatus.FAILED;
        return new BackupRun(runId, job.name(), job.repositoryName(), job.machineName(),
            status, startedAt, now, exitCode, job.archiveNameTemplate(), summary);
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
     * same rule as {@link #fromExitCode} — {@code 0} is {@code SUCCESS}, any non-zero code is
     * {@code FAILED} — preserving the run's identity (id, job, repository, machine, archive, start time)
     * and stamping the finish instant, exit code and {@code summary}. This is the wither a poll uses to
     * promote an in-flight run to its outcome.
     */
    public BackupRun completedFrom(int exitCode, Instant now, String summary) {
        BackupRunStatus terminal = exitCode == 0 ? BackupRunStatus.SUCCESS : BackupRunStatus.FAILED;
        return new BackupRun(runId, jobName, repositoryName, machineName, terminal,
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
