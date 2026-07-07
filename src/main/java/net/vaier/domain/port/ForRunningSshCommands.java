package net.vaier.domain.port;

import net.vaier.domain.CommandResult;
import net.vaier.domain.SshTarget;

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

    /** Run {@code command} on {@code target} and return its captured output and exit status. */
    CommandResult run(SshTarget target, String command);
}
