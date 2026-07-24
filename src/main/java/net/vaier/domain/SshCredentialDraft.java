package net.vaier.domain;

/**
 * The SSH login an operator supplies while a machine is still only a discovered candidate: a username,
 * an {@link AuthMethod}, the secret material, and an optional key passphrase — everything a
 * {@link HostCredential} holds except the machine identity, which does not exist yet.
 *
 * <p>It is the shape both halves of "attach a credential during adoption" work from: a pre-registration
 * {@link #targetAt test target} (which pins nothing — nothing is trusted for a machine never connected
 * to) and, once the machine is registered, the {@link #forMachine vault credential} keyed to its new
 * name. Keeping both derivations here means "a tested-but-unregistered credential pins no host key" and
 * "an adopted credential is unmanaged and keyed to the machine's identity" are decided in one place.
 */
public record SshCredentialDraft(String username, AuthMethod authMethod, String secret, String passphrase) {

    /**
     * The {@link SshTarget} to test this credential against {@code address}:{@code port}, with no pinned
     * fingerprint — a pre-registration test trusts on first use and records nothing.
     */
    public SshTarget targetAt(String address, int port) {
        return new SshTarget(address, port, username, authMethod, secret, passphrase, null);
    }

    /**
     * This draft as the vault {@link HostCredential} for the machine identified by {@code machineId}. Always unmanaged
     * ({@code managed=false}) — Vaier did not generate this keypair; the operator supplied it.
     */
    public HostCredential forMachine(MachineId machineId) {
        return new HostCredential(machineId, username, authMethod, secret, passphrase, false);
    }
}
