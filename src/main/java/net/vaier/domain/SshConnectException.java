package net.vaier.domain;

/**
 * Thrown when Vaier cannot establish an SSH session to a machine because the transport failed —
 * connection refused, unreachable host, or a connect/handshake timeout. Distinct from
 * {@link SshAuthException} (credentials rejected) and {@link HostKeyMismatchException} (pinned host
 * key changed) so the terminal can show the operator a precise, actionable reason.
 */
public class SshConnectException extends RuntimeException {
    public SshConnectException(String message) {
        super(message);
    }

    public SshConnectException(String message, Throwable cause) {
        super(message, cause);
    }
}
