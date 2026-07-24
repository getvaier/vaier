package net.vaier.domain;

/**
 * Everything the SSH adapter needs to open one shell session to a machine: where to connect
 * ({@code host}:{@code port}), the login ({@code username} + {@link AuthMethod} + {@code secret} and
 * optional key {@code passphrase}), and the host-key {@code pinnedFingerprint} to enforce (null when
 * none is pinned yet — first use pins it). Assembled by the application from the machine's resolved
 * SSH address and its vault credential; it carries reversible secrets and never leaves the process.
 */
public record SshTarget(String host, int port, String username, AuthMethod authMethod,
                        String secret, String passphrase, String pinnedFingerprint,
                        MachineId machineId) {

    /**
     * A target for a machine Vaier has no identity for — a pre-registration credential test against a
     * bare address. Nothing may be pinned for it, because there is nothing to pin it against.
     */
    public SshTarget(String host, int port, String username, AuthMethod authMethod,
                     String secret, String passphrase, String pinnedFingerprint) {
        this(host, port, username, authMethod, secret, passphrase, pinnedFingerprint, null);
    }

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
            credential.secret(), credential.passphrase(), pinnedFingerprint, credential.machineId());
    }

    /**
     * Trust-on-first-use: whether the fingerprint this machine just presented should be pinned — true
     * only when nothing was pinned yet and the host actually presented a key. A machine whose key already
     * matches is not re-pinned, and a {@link HostKeyTrust#MISMATCH mismatch} is a refusal, never a
     * silent re-pin over the old key.
     *
     * <p>Every path that connects to a machine — the shell, a remote command, an Explorer listing — asks
     * this one question, so the rule is decided here rather than hand-rolled in each service.
     */
    public boolean needsPinning(String presentedFingerprint) {
        return presentedFingerprint != null
            && HostKeyTrust.evaluate(pinnedFingerprint, presentedFingerprint) == HostKeyTrust.PIN_NEW;
    }

    /**
     * Trust-on-first-use, carried out: pin what the machine presented, if and only if
     * {@link #needsPinning} says it should be. The port is handed in and called here, so the rule and the
     * act of recording it stay together.
     *
     * <p>The machine pinned for is this target's own {@link #machineId()}, not a name passed alongside:
     * a caller that could supply the wrong one would pin a host's key against a different machine. A
     * target with no identity — a pre-registration credential test — pins nothing.
     *
     * <p>Every path that reaches a machine over SSH — the shell, a remote command, an Explorer listing, a
     * disk reading — must pin on first use, or a machine touched only by that path would never gain a
     * pinned key and could never detect one changing. Each service used to keep its own copy of this
     * three-line dance; a third copy (the disk read) is what made it worth having exactly one.
     */
    public void pinOnFirstUse(String presentedFingerprint,
                              net.vaier.domain.port.ForTrackingHostKeys hostKeys) {
        if (machineId != null && needsPinning(presentedFingerprint)) {
            hostKeys.pin(machineId, presentedFingerprint);
        }
    }
}
