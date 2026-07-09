package net.vaier.application;

public interface PrepareBackupClientUseCase {

    /**
     * Prepare a backup client by installing borg on the host over SSH — the client-side counterpart to
     * {@link ProvisionBackupServerUseCase#provision}. Because an {@code apt-get}/{@code dnf} install can
     * exceed the 20 s SSH exec cap, the install script is launched <em>detached</em> ({@code nohup}) and this
     * returns as soon as the launch is confirmed ({@code started}); progress is then read with
     * {@link #prepareClientStatus}. The install needs root and Vaier SSHes as a non-root user, so it first
     * probes for passwordless sudo: with it, Vaier runs the script itself under {@code sudo -n}; without it
     * (or when the host cannot be reached) it returns a {@code scriptOnly} result pointing the operator at
     * the staged script to run with {@code sudo bash}. Never throws.
     */
    PrepareResult prepareClient(String machineName);

    /**
     * The current state of a launched client-prepare run, read over SSH from the detached run's on-host
     * {@code .rc}/{@code .log} files (the same generic poll the server provisioning uses). Never throws: a
     * poll that times out or errors leaves the state {@code RUNNING} rather than reporting a false outcome.
     */
    PrepareStatus prepareClientStatus(String machineName);

    /**
     * The outcome of a prepare attempt, mirroring {@link ProvisionBackupServerUseCase.ProvisionResult}'s
     * shape and semantics so the readiness-panel UI can drive it identically: {@code prepared} is reserved
     * for a fully-settled synchronous success (unused now the install is detached); {@code started} when the
     * detached install launched and the caller should poll {@link #prepareClientStatus}; {@code scriptOnly}
     * when Vaier could not run it itself (no passwordless sudo, or the host is not reachable) and the operator
     * must run the staged script; {@code message} is a human-readable reason for any path (never a secret).
     *
     * <p>{@code stagedScriptPath} is the absolute on-host path Vaier wrote the install script to when it could
     * SSH the machine but not gain root, so the UI can render {@code sudo bash <path>} precisely rather than
     * parsing the message prose. It is {@code null} on every other path. A non-null {@code stagedScriptPath}
     * always implies {@code scriptOnly}.
     */
    record PrepareResult(boolean prepared, boolean scriptOnly, boolean started, String message,
                         String stagedScriptPath) {}

    /** Whether a launched client-prepare is still running, has succeeded, or has failed. */
    enum PrepareState { RUNNING, SUCCESS, FAILED }

    /**
     * A launched prepare's current {@code state} and a tail of its on-host install log (empty while still
     * running or when the log cannot be read). Carries no secret.
     */
    record PrepareStatus(PrepareState state, String logTail) {}
}
