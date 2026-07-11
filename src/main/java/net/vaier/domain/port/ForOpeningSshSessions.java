package net.vaier.domain.port;

import net.vaier.domain.SshTarget;

/**
 * Driven port for opening a live SSH shell session to a machine — the bridge the web terminal drives.
 * The adapter authenticates from the {@link SshTarget}, enforces host-key trust (TOFU), requests a PTY
 * and shell, and streams the remote output to the {@link SshOutputListener}. Failures surface as the
 * domain SSH exceptions ({@code SshConnectException}, {@code SshAuthException},
 * {@code HostKeyMismatchException}).
 */
public interface ForOpeningSshSessions {

    /**
     * Open an interactive PTY session to {@code target} running {@code command}, delivering remote output
     * to {@code onOutput}. The {@code command} is a shell decision the domain makes (see {@code
     * PersistentShell}) — typically a tmux attach-or-create with a plain-shell fallback — run under a PTY
     * so it behaves exactly like an interactive login. The returned {@link SshSession} is the live handle:
     * write keystrokes, resize the PTY, and close it.
     */
    SshSession open(SshTarget target, String command, SshOutputListener onOutput);

    /** A live SSH shell session. Thread-safe writes/close; closing is idempotent. */
    interface SshSession {

        /** Send raw bytes (keystrokes) to the remote shell's stdin. */
        void write(byte[] data);

        /** Resize the remote PTY to {@code cols}x{@code rows}. */
        void resize(int cols, int rows);

        /**
         * The SSH host-key fingerprint the server presented on connect (e.g. {@code SHA256:…}). Used
         * by the caller to pin it on first use.
         */
        String hostKeyFingerprint();

        /** Close the channel and the underlying session; idempotent. */
        void close();
    }

    /** Sink for remote shell output and end-of-session. */
    interface SshOutputListener {

        /** Bytes produced by the remote shell (stdout/stderr merged over the PTY). */
        void onOutput(byte[] data);

        /** The remote shell ended or the session dropped; the terminal should close. */
        default void onClosed() {
        }
    }
}
