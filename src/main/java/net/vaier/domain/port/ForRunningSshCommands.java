package net.vaier.domain.port;

import net.vaier.domain.CommandResult;
import net.vaier.domain.SshTarget;

import java.time.Duration;

/**
 * Driven port for running one non-interactive command on a machine over SSH and reading its result —
 * the counterpart to {@link ForOpeningSshSessions}, which streams an interactive shell. Where the web
 * terminal opens a PTY for a human, this opens an exec channel for code that needs an answer: it runs
 * the command, captures stdout/stderr and the exit code, and returns a {@link CommandResult}.
 *
 * <p>The adapter authenticates from the {@link SshTarget} and enforces host-key trust (TOFU) exactly
 * as the shell path does; transport and auth failures surface as the domain SSH exceptions
 * ({@code SshConnectException}, {@code SshAuthException}, {@code HostKeyMismatchException}). A non-zero
 * exit code is a normal result, never an exception. The run is bounded by a hard timeout and the
 * captured output is capped, so a hung or chatty command can neither hang nor OOM Vaier.
 */
public interface ForRunningSshCommands {

    /**
     * Run {@code command} on {@code target} under the adapter's own short default deadline — the right
     * bound for the probes and one-line reads that make up almost every remote command Vaier runs.
     */
    CommandResult run(SshTarget target, String command);

    /**
     * As {@link #run(SshTarget, String)}, but bounded by {@code timeout} instead of the default.
     *
     * <p>The default is measured in seconds, which is the whole reason this exists: a command that is
     * <em>expected</em> to take minutes — pulling a container image over a home connection, recreating a
     * compose service — would be abandoned mid-flight and reported as a timeout while the host was still
     * working. The caller that knows how long its command legitimately takes says so here rather than every
     * other caller paying for it in a raised global default.
     */
    CommandResult run(SshTarget target, String command, Duration timeout);

    /**
     * As {@link #run(SshTarget, String, Duration)}, with {@code stdin} written to the command as one line and
     * the input then closed. The way to hand a command a secret: a command line shows in {@code ps} and logs.
     */
    CommandResult run(SshTarget target, String command, Duration timeout, String stdin);
}
