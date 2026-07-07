package net.vaier.application;

import net.vaier.domain.CommandResult;

public interface RunRemoteCommandUseCase {

    /**
     * Run {@code command} on the machine named {@code machineName} over SSH and return its captured
     * output and exit status. Resolves the machine's SSH address, authenticates from the credential
     * vault, and pins the host key on first use (TOFU) — the same resolution + credential + pin logic
     * the web terminal uses. A non-zero exit code is a normal result, never an exception.
     *
     * @throws net.vaier.domain.NotFoundException         the machine name is unknown
     * @throws net.vaier.domain.NoHostCredentialException no credential is stored for the machine
     * @throws net.vaier.domain.HostKeyMismatchException  the host key changed from the pinned one
     * @throws net.vaier.domain.SshAuthException          the stored credential was rejected
     * @throws net.vaier.domain.SshConnectException       the host could not be reached
     */
    CommandResult run(String machineName, String command);
}
