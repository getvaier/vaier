package net.vaier.domain;

/**
 * Thrown when a terminal is requested for a machine that has no host credential stored in the vault.
 * The operator must add an SSH credential before a shell can be opened — distinct from an auth failure
 * (a credential exists but is wrong).
 */
public class NoHostCredentialException extends RuntimeException {
    public NoHostCredentialException(String machineName) {
        super("No SSH credential is stored for \"" + machineName + "\". Add one before opening a terminal.");
    }
}
