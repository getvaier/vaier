package net.vaier.domain;

/**
 * Everything the SSH adapter needs to open one shell session to a machine: where to connect
 * ({@code host}:{@code port}), the login ({@code username} + {@link AuthMethod} + {@code secret} and
 * optional key {@code passphrase}), and the host-key {@code pinnedFingerprint} to enforce (null when
 * none is pinned yet — first use pins it). Assembled by the application from the machine's resolved
 * SSH address and its vault credential; it carries reversible secrets and never leaves the process.
 */
public record SshTarget(String host, int port, String username, AuthMethod authMethod,
                        String secret, String passphrase, String pinnedFingerprint) {

    /** The IANA default SSH port. */
    public static final int DEFAULT_PORT = 22;

    public SshTarget {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("SSH host must not be blank");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("SSH username must not be blank");
        }
        if (authMethod == null) {
            throw new IllegalArgumentException("authMethod must not be null");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SSH port must be between 1 and 65535 (was " + port + ")");
        }
    }

    /** Builds a target for {@code host} on the {@link #DEFAULT_PORT default port} from a credential. */
    public static SshTarget on(String host, HostCredential credential, String pinnedFingerprint) {
        return new SshTarget(host, DEFAULT_PORT, credential.username(), credential.authMethod(),
            credential.secret(), credential.passphrase(), pinnedFingerprint);
    }
}
