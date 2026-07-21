package net.vaier.application;

import net.vaier.domain.SshCredentialDraft;
import net.vaier.domain.SshCredentialVerification;

/**
 * Test an SSH credential against a machine's address <em>before</em> the machine is registered — the
 * green-check the operator hits in the adopt sheet while the host is still only a discovered candidate.
 * Because it must be testable before the machine exists, it is addressed by host + port, not by machine
 * name, and it persists nothing: no credential is stored and no host key is pinned.
 *
 * <p>It never throws for the ordinary outcomes (bad credential, unreachable host) — those come back as a
 * {@link SshCredentialVerification} result, like the backup prereq probes.
 */
public interface VerifySshCredentialUseCase {

    /** Probe {@code credential} against {@code address}:{@code port} and report the result. */
    SshCredentialVerification verify(String address, int port, SshCredentialDraft credential);
}
