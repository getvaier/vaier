package net.vaier.application;

import net.vaier.domain.PersistentShell;
import net.vaier.domain.port.ForOpeningSshSessions.SshOutputListener;
import net.vaier.domain.port.ForOpeningSshSessions.SshSession;

public interface OpenTerminalSessionUseCase {

    /**
     * Open a live SSH shell to the machine named {@code machineName} for the browser pane {@code paneId},
     * streaming remote output to {@code onOutput}. Resolves the machine's SSH address (peer tunnel IP /
     * LAN address / Vaier host), authenticates from the credential vault, and pins the host key on first
     * use.
     *
     * <p>The shell is opened as a <b>persistent shell</b>: a tmux session named for the pane (stable
     * across reconnects, distinct between panes), so it outlives a Vaier redeploy and a reconnect
     * <b>reattaches</b> to it rather than starting fresh. When tmux is not installed on the target a plain
     * login shell is opened instead. The returned {@link OpenedTerminal} carries the live session together
     * with the {@link PersistentShell.Continuity continuity} — whether this open reattached, started a new
     * session, or fell back to a plain shell — so the caller can report it truthfully.
     *
     * @throws net.vaier.domain.NotFoundException        the machine name is unknown
     * @throws net.vaier.domain.NoHostCredentialException no credential is stored for the machine
     * @throws net.vaier.domain.HostKeyMismatchException the host key changed from the pinned one
     * @throws net.vaier.domain.SshAuthException         the stored credential was rejected
     * @throws net.vaier.domain.SshConnectException      the host could not be reached
     */
    OpenedTerminal openTerminal(String machineName, String paneId, SshOutputListener onOutput);

    /**
     * The live {@link SshSession} and how the open resolved (reattached / new / plain).
     */
    record OpenedTerminal(SshSession session, PersistentShell.Continuity continuity) {
    }
}
