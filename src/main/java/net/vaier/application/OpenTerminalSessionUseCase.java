package net.vaier.application;

import net.vaier.domain.port.ForOpeningSshSessions.SshOutputListener;
import net.vaier.domain.port.ForOpeningSshSessions.SshSession;

public interface OpenTerminalSessionUseCase {

    /**
     * Open a live SSH shell to the machine named {@code machineName}, streaming remote output to
     * {@code onOutput}. Resolves the machine's SSH address (peer tunnel IP / LAN address / Vaier host),
     * authenticates from the credential vault, and pins the host key on first use.
     *
     * @throws net.vaier.domain.NotFoundException        the machine name is unknown
     * @throws net.vaier.domain.NoHostCredentialException no credential is stored for the machine
     * @throws net.vaier.domain.HostKeyMismatchException the host key changed from the pinned one
     * @throws net.vaier.domain.SshAuthException         the stored credential was rejected
     * @throws net.vaier.domain.SshConnectException      the host could not be reached
     */
    SshSession openTerminal(String machineName, SshOutputListener onOutput);
}
