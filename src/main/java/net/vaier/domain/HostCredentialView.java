package net.vaier.domain;

/**
 * The redacted, safe-to-leave-the-process shape of a {@link HostCredential}: it reports which machine
 * the credential is for (by {@link MachineId}, not by a label that can change under it), its username and auth method, and merely <em>whether</em> a secret is held —
 * never the secret or passphrase bytes. This is the only shape a GET may return to the browser.
 */
public record HostCredentialView(MachineId machineId, String username, AuthMethod authMethod, boolean hasSecret) {
}
