package net.vaier.application;

/**
 * Trust a backup client host's SSH key on a {@link net.vaier.domain.BackupServer}, so borg — which runs
 * on the client as the SSH user from {@code host-credentials.yml} (e.g. {@code geir}, <em>not</em> root)
 * — can authenticate to the borg server's sshd. Without this the client's key is absent from the server's
 * {@code authorized_keys} and every {@code borg} invocation fails with {@code Permission denied
 * (publickey)}, even while the reachability probe reports all-green. Closes #320.
 *
 * <p>The flow is two SSH hops: generate (only if absent) the client's {@code id_ed25519} key pair and read
 * its public key <em>on the client</em>, then append that public key — idempotently and newline-safely —
 * to {@code authorized_keys} <em>on the backup server's machine</em>. The public key is not a secret.
 */
public interface AuthorizeBackupClientUseCase {

    /**
     * Authorize the client {@code machineName} on the named backup server. Never throws: an unknown server,
     * a guarded-out (unknown / SSH-disabled / credential-less) client or server machine, a backup server
     * with no data path (so no {@code authorized_keys} to write), a client that returns no valid public
     * key, or an SSH failure each come back as a reasoned negative {@link AuthorizeResult} rather than an
     * exception — Vaier never writes junk into {@code authorized_keys}.
     */
    AuthorizeResult authorizeClient(String serverName, String machineName);

    /**
     * The outcome of an authorize attempt: {@code authorized} when the client key is trusted on the server
     * afterwards (true whether it was just added or already present); {@code alreadyTrusted} when it was
     * already present and the append was a no-op; {@code hostKeyPinned} when Vaier could read the server's
     * published host keys and pin them in the client's {@code known_hosts} (Slice 8) — {@code false} on an
     * adopted/not-yet-provisioned server whose host-key file is absent, where the key is still authorized but
     * the operator must pin manually or re-run the setup script; {@code message} a human-readable reason for
     * any path (never a secret — the private key never leaves the client and is never logged).
     *
     * <p>{@code hostKeyPinned} is a separate boolean rather than folded into {@code authorized} because the
     * two outcomes are orthogonal: a client key can be authorized on the server while the server's host key
     * could not be pinned (the adopted-server case), and the UI must be able to warn about exactly that.
     */
    record AuthorizeResult(boolean authorized, boolean alreadyTrusted, boolean hostKeyPinned, String message) {}
}
