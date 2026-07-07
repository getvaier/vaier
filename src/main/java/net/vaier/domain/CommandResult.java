package net.vaier.domain;

/**
 * The outcome of running one non-interactive command on a machine over SSH: the process
 * {@code exitCode} (or {@code -1} when it could not be determined — e.g. the command timed out or the
 * server never sent an exit status), the captured {@code stdout} and {@code stderr} (each bounded so a
 * chatty command cannot exhaust memory), and {@code timedOut} when the command exceeded the run
 * deadline and was abandoned. A non-zero {@code exitCode} is a normal result, not an error — callers
 * inspect it rather than catching an exception.
 */
public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
}
