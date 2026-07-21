package net.vaier.domain.port;

import net.vaier.domain.SshTarget;

/**
 * Driven port for the pre-registration SSH credential probe: attempt a single connect + authenticate
 * to {@code target} and report the host-key fingerprint the machine presented. It persists nothing —
 * no credential is stored and no host key is pinned — so an operator can test a login while the
 * machine is still only a discovered candidate.
 *
 * <p>Sibling of {@link ForVerifyingSmtpCredentials}: it signals the ordinary failures by throwing the
 * distinct domain SSH exceptions rather than a status flag — {@code SshConnectException} when the host
 * is unreachable and {@code SshAuthException} when it answers but rejects the credential — and the
 * domain ({@code SshCredentialVerification}) maps those into a result value object. The verify use case
 * therefore never surfaces them as errors; they are normal outcomes of a test.
 */
public interface ForVerifyingSshCredentials {

    /**
     * Connect and authenticate to {@code target}, returning the SHA-256 host-key fingerprint the host
     * presented on success. Nothing is written to any store.
     *
     * @throws net.vaier.domain.SshConnectException when the transport fails (host unreachable/refused)
     * @throws net.vaier.domain.SshAuthException    when the host is reached but the credential is rejected
     */
    String probe(SshTarget target);
}
