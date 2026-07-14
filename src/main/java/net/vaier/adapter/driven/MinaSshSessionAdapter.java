package net.vaier.adapter.driven;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import net.vaier.adapter.driven.SshConnector.Connection;
import net.vaier.domain.CommandResult;
import net.vaier.domain.HostKeyTrust;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForOpeningSshSessions;
import net.vaier.domain.port.ForRunningSshCommands;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.stereotype.Component;

/**
 * Apache MINA sshd client adapter — the bridge that opens a real SSH shell to a machine and streams it
 * to the web terminal. Authenticates by password or private key (with optional passphrase), enforces
 * host-key trust-on-first-use via the domain {@link HostKeyTrust} decision, requests an {@code xterm}
 * PTY + shell, and pumps remote output to the listener while forwarding keystrokes and window resizes.
 *
 * <p>It also runs one-shot, non-interactive commands via {@link #run} (an exec channel rather than a
 * PTY shell), capturing stdout/stderr and the exit code into a {@code CommandResult} under a hard
 * timeout and a bounded output cap. Both paths share one copy of the connect + host-key TOFU + auth
 * logic ({@link SshConnector}), as does the Explorer's SFTP adapter.
 *
 * <p>Each {@link #open} and {@link #run} uses its own short-lived {@link SshClient} so per-connection
 * host-key verification and lifecycle stay isolated. Transport, auth and host-key failures surface as
 * the distinct domain SSH exceptions so the caller can show the operator a precise reason.
 */
@Component
@Slf4j
public class MinaSshSessionAdapter implements ForOpeningSshSessions, ForRunningSshCommands {

    private static final Duration CHANNEL_TIMEOUT = Duration.ofSeconds(12);
    private static final String PTY_TYPE = "xterm-256color";
    private static final int INITIAL_COLS = 80;
    private static final int INITIAL_ROWS = 24;

    /** Hard bound on how long a single {@link #run} command may take before it is abandoned. */
    private static final Duration DEFAULT_EXEC_TIMEOUT = Duration.ofSeconds(20);
    /** Cap on captured stdout/stderr per stream (1 MiB) so a chatty command cannot exhaust memory. */
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;

    private final Duration execTimeout;
    private final int maxOutputBytes;

    public MinaSshSessionAdapter() {
        this(DEFAULT_EXEC_TIMEOUT, DEFAULT_MAX_OUTPUT_BYTES);
    }

    MinaSshSessionAdapter(Duration execTimeout, int maxOutputBytes) {
        this.execTimeout = execTimeout;
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public CommandResult run(SshTarget target, String command) {
        Connection conn = SshConnector.establish(target);
        ChannelExec channel = null;
        try {
            channel = conn.session().createExecChannel(command);
            BoundedOutputStream out = new BoundedOutputStream(maxOutputBytes);
            BoundedOutputStream err = new BoundedOutputStream(maxOutputBytes);
            channel.setOut(out);
            channel.setErr(err);
            channel.open().verify(CHANNEL_TIMEOUT);
            // Bounded wait: if CLOSED never arrives within the deadline the command is abandoned,
            // so a hung command can neither block Vaier nor leak the connection.
            Set<ClientChannelEvent> events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), execTimeout);
            boolean timedOut = !events.contains(ClientChannelEvent.CLOSED);
            Integer exit = channel.getExitStatus();
            // -1 stands for "unknown": a timeout, or a server that never sent an exit status. A known
            // non-zero exit is a normal result and is surfaced as-is, never thrown.
            int exitCode = (timedOut || exit == null) ? -1 : exit;
            return new CommandResult(exitCode, out.text(), err.text(), timedOut, conn.fingerprint());
        } catch (IOException e) {
            throw new SshConnectException(
                "Could not run a command on " + target.host() + " (" + SshConnector.rootMessage(e) + ")", e);
        } finally {
            closeChannelQuietly(channel);
            SshConnector.closeQuietly(conn.session(), conn.client());
        }
    }

    @Override
    public SshSession open(SshTarget target, String command, SshOutputListener onOutput) {
        Connection conn = SshConnector.establish(target);
        ChannelExec channel = openPtyCommand(conn.client(), conn.session(), target, command, onOutput);

        channel.addCloseFutureListener(f -> onOutput.onClosed());
        return new MinaSshSession(conn.client(), conn.session(), channel, conn.fingerprint());
    }

    /**
     * Open {@code command} under an interactive PTY (an exec channel with {@code usePty} set), rather than
     * a bare login shell, so the domain can decide what runs — a tmux attach-or-create with a plain-shell
     * fallback (see {@code PersistentShell}). {@code exec}-ing tmux/the shell inside {@code command} keeps
     * the process tree clean, and the PTY makes it behave exactly like an interactive login.
     */
    private ChannelExec openPtyCommand(SshClient client, ClientSession session, SshTarget target,
                                       String command, SshOutputListener onOutput) {
        try {
            ChannelExec channel = session.createExecChannel(command);
            channel.setUsePty(true);
            channel.setPtyType(PTY_TYPE);
            channel.setPtyColumns(INITIAL_COLS);
            channel.setPtyLines(INITIAL_ROWS);
            channel.setOut(new ListenerOutputStream(onOutput));
            channel.setErr(new ListenerOutputStream(onOutput));
            channel.open().verify(CHANNEL_TIMEOUT);
            return channel;
        } catch (IOException e) {
            SshConnector.closeQuietly(session, client);
            throw new SshConnectException("Could not open a shell on " + target.host() + " (" + SshConnector.rootMessage(e) + ")", e);
        }
    }

    private static void closeChannelQuietly(ClientChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close(false);
        } catch (Exception ignored) {
            // best effort
        }
    }

    /** An {@link OutputStream} that forwards every write to the terminal's output listener. */
    private static final class ListenerOutputStream extends OutputStream {
        private final SshOutputListener listener;

        ListenerOutputStream(SshOutputListener listener) {
            this.listener = listener;
        }

        @Override
        public void write(int b) {
            listener.onOutput(new byte[]{(byte) b});
        }

        @Override
        public void write(byte[] b, int off, int len) {
            byte[] copy = new byte[len];
            System.arraycopy(b, off, copy, 0, len);
            listener.onOutput(copy);
        }
    }

    /**
     * An {@link OutputStream} that accumulates at most {@code cap} bytes and silently drops the rest, so
     * a chatty command's stdout/stderr can never exhaust Vaier's heap.
     */
    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int cap;
        private int written;

        BoundedOutputStream(int cap) {
            this.cap = cap;
        }

        @Override
        public synchronized void write(int b) {
            if (written < cap) {
                buffer.write(b);
                written++;
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int room = cap - written;
            if (room <= 0) {
                return;
            }
            int n = Math.min(room, len);
            buffer.write(b, off, n);
            written += n;
        }

        synchronized String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /** Live handle over a MINA PTY channel; write/resize/close are best-effort and close is idempotent. */
    private static final class MinaSshSession implements SshSession {
        private final SshClient client;
        private final ClientSession session;
        private final ChannelExec channel;
        private final OutputStream toRemote;
        private final String fingerprint;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        MinaSshSession(SshClient client, ClientSession session, ChannelExec channel, String fingerprint) {
            this.client = client;
            this.session = session;
            this.channel = channel;
            this.toRemote = channel.getInvertedIn();
            this.fingerprint = fingerprint;
        }

        @Override
        public void write(byte[] data) {
            try {
                toRemote.write(data);
                toRemote.flush();
            } catch (IOException e) {
                log.debug("Write to remote shell failed: {}", e.getMessage());
            }
        }

        @Override
        public void resize(int cols, int rows) {
            try {
                channel.sendWindowChange(cols, rows);
            } catch (IOException e) {
                log.debug("PTY resize failed: {}", e.getMessage());
            }
        }

        @Override
        public String hostKeyFingerprint() {
            return fingerprint;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                channel.close(false);
            } catch (Exception ignored) {
                // best effort
            }
            try {
                session.close(false);
            } catch (Exception ignored) {
                // best effort
            }
            client.stop();
        }
    }
}
