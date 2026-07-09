package net.vaier.application;

public interface ProvisionBackupServerUseCase {

    /**
     * Provision the named {@link net.vaier.domain.BackupServer} by launching its bootstrap setup script on
     * the host over SSH — where docker-over-SSH is available. Because the setup's {@code docker compose up
     * -d} pulls a ~100 MB image and would blow the 20 s SSH exec cap, the script is launched <em>detached</em>
     * ({@code nohup}) and this returns as soon as the launch is confirmed ({@code started}); progress is then
     * read with {@link #provisionStatus}. This never throws: it first probes for a usable {@code docker} CLI
     * and, when one is absent (as on the Synology NAS, which exposes no docker over SSH) or the host cannot be
     * reached (unknown machine, SSH off, no stored credential), it returns a {@code scriptOnly} result
     * pointing the operator at the downloadable setup script rather than an opaque failure.
     */
    ProvisionResult provision(String serverName);

    /**
     * The current state of a launched provision run, read over SSH from the detached run's on-host
     * {@code .rc}/{@code .log} files. Never throws: a poll that times out or errors leaves the state
     * {@code RUNNING} rather than reporting a false outcome.
     */
    ProvisionStatus provisionStatus(String serverName);

    /**
     * The outcome of a provision attempt: {@code provisioned} is reserved for a fully-settled synchronous
     * success (unused now the run is detached); {@code started} when the detached setup script launched and
     * the caller should poll {@link #provisionStatus}; {@code scriptOnly} when Vaier could not run it itself
     * (no docker-over-SSH, or the host is not reachable) and the operator must run the setup script on the
     * host; {@code message} is a human-readable reason for any path (never a secret).
     *
     * <p>{@code stagedScriptPath} is the absolute on-host path Vaier wrote the setup script to when it could
     * SSH the machine but not drive its docker (the Synology case), so the UI can render {@code sudo bash
     * <path>} precisely rather than parsing the message prose. It is {@code null} on every other path — a
     * launched or failed provision, an unreachable host, or a staging failure where the operator must instead
     * download the script from the UI. A non-null {@code stagedScriptPath} always implies {@code scriptOnly}.
     */
    record ProvisionResult(boolean provisioned, boolean scriptOnly, boolean started, String message,
                           String stagedScriptPath) {}

    /** Whether a launched provision is still running, has succeeded, or has failed. */
    enum ProvisionState { RUNNING, SUCCESS, FAILED }

    /**
     * A launched provision's current {@code state} and a tail of its on-host setup log (empty while still
     * running or when the log cannot be read). Carries no secret.
     */
    record ProvisionStatus(ProvisionState state, String logTail) {}
}
