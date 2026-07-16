package net.vaier.domain.port;

/**
 * A driven port the domain calls to <em>ready</em> a machine's host for backups — the infrastructure side of
 * the decision the {@link net.vaier.domain.BackupJob} makes on a machine's FIRST back-up: trust the machine's
 * SSH key on its backup server and install the borg client where it is missing. The operator never runs the
 * provisioning wizard by hand; the domain triggers this as a side-effect the moment a machine earns its first
 * back-up (a newly-created job), because the operator said the data matters.
 *
 * <p>The implementation reuses the existing key-trust and prepare-client mechanics. Readying is idempotent (an
 * already-trusted key and an already-installed borg are no-ops) and the install is <em>detached</em> — the
 * caller does not block on it; progress is pushed on the {@code prepare-client-settled} SSE event. It must
 * <strong>never throw</strong>: any failure comes back as a reasoned {@link ReadyingOutcome} so a readying
 * failure can never fail the back-up itself.
 */
public interface ForReadyingBackupClients {

    /**
     * Ready {@code machineName}'s host for backups: authorize its key on its backup server, then launch the
     * detached borg-client install. Never throws — a failure is reported in the outcome's {@code message}.
     */
    ReadyingOutcome readyForBackup(String machineName);

    /**
     * The outcome of readying a host (never a secret): {@code started} when the borg-client install launched
     * (detached); {@code scriptOnly} when Vaier could not run it itself and the operator must run the staged
     * script; {@code stagedScriptPath} the absolute on-host path for that case (else {@code null}); {@code
     * message} a human-readable reason for any path.
     */
    record ReadyingOutcome(boolean started, boolean scriptOnly, String stagedScriptPath, String message) {}
}
