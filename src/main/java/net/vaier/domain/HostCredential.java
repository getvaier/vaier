package net.vaier.domain;

/**
 * The single host credential Vaier holds for a machine so it can open an SSH session to it: the
 * login {@code username}, the {@link AuthMethod}, the {@code secret} (the password, or the private-key
 * PEM), and an optional key {@code passphrase}. {@code managed} marks a credential whose keypair Vaier
 * itself generated (always false in slice 1; slice 3 turns it on).
 *
 * <p>The secret material lives here in the clear — encryption at rest is a persistence concern the
 * adapter applies on the way to disk, so the domain stays free of it. Whether a value is safe to
 * expose is a domain decision: {@link #toView()} produces the redacted {@link HostCredentialView} that
 * is the only shape allowed to leave the process.
 */
public record HostCredential(MachineId machineId, String username, AuthMethod authMethod,
                             String secret, String passphrase, boolean managed) {

    public HostCredential {
        if (machineId == null) {
            throw new IllegalArgumentException("machineId must not be null");
        }
        requireNonBlank(username, "username");
        requireNonBlank(secret, "secret");
        if (authMethod == null) {
            throw new IllegalArgumentException("authMethod must not be null");
        }
    }

    /** The redacted view of this credential — carries no secret or passphrase bytes. */
    public HostCredentialView toView() {
        return new HostCredentialView(machineId, username, authMethod, secret != null && !secret.isBlank());
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
