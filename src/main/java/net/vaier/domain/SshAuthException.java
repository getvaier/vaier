package net.vaier.domain;

/**
 * Thrown when an SSH session reaches the server but the stored credential is rejected — wrong
 * password, or a private key the host won't accept. Distinct from {@link SshConnectException}
 * (transport failure) so the terminal tells the operator to fix the credential, not the network.
 */
public class SshAuthException extends RuntimeException {
    public SshAuthException(String message) {
        super(message);
    }

    public SshAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
