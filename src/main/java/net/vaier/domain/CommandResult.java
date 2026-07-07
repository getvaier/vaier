package net.vaier.domain;

/**
 * The outcome of running one non-interactive command on a machine over SSH: the process
 * {@code exitCode} (or {@code -1} when it could not be determined — e.g. the command timed out or the
 * server never sent an exit status), the captured {@code stdout} and {@code stderr} (each bounded so a
 * chatty command cannot exhaust memory), {@code timedOut} when the command exceeded the run deadline
 * and was abandoned, and the {@code hostKeyFingerprint} the server presented on this connect (null when
 * unknown). A non-zero {@code exitCode} is a normal result, not an error — callers inspect it rather
 * than catching an exception.
 *
 * <p>The {@code hostKeyFingerprint} is the exec-path counterpart to
 * {@link net.vaier.domain.port.ForOpeningSshSessions.SshSession#hostKeyFingerprint()}: it lets the
 * application pin a not-yet-trusted host on first use (TOFU) from a command run, exactly as the
 * terminal path does from a shell session.
 */
public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut,
                            String hostKeyFingerprint) {
}
