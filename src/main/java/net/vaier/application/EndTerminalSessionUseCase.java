package net.vaier.application;

/**
 * End a pane's persistent shell for good — the counterpart to
 * {@link OpenTerminalSessionUseCase#openTerminal}.
 *
 * <p>A persistent shell deliberately outlives a dropped connection: that is what lets a reconnect, or a
 * redeploy of Vaier itself, reattach to the same tmux session with its cwd and scrollback intact. The cost of
 * that promise is that nothing else ever ends the session — so closing a pane has to say so explicitly, or the
 * shell stays detached on the machine forever, still running whatever was inside it.
 *
 * <p>Best-effort by contract: this runs on a close path, after the operator has already dismissed the pane and
 * moved on. An unreachable host or a missing credential is not worth an error they cannot act on.
 */
public interface EndTerminalSessionUseCase {

    /** End the persistent shell for {@code paneId} on {@code machineName}. Never throws. */
    void endTerminal(String machineName, String paneId);
}
