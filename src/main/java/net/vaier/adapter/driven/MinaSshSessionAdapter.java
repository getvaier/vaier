package net.vaier.adapter.driven;

import java.io.IOException;
import java.io.OutputStream;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.HostKeyMismatchException;
import net.vaier.domain.HostKeyTrust;
import net.vaier.domain.SshAuthException;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForOpeningSshSessions;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.springframework.stereotype.Component;

/**
 * Apache MINA sshd client adapter — the bridge that opens a real SSH shell to a machine and streams it
 * to the web terminal. Authenticates by password or private key (with optional passphrase), enforces
 * host-key trust-on-first-use via the domain {@link HostKeyTrust} decision, requests an {@code xterm}
 * PTY + shell, and pumps remote output to the listener while forwarding keystrokes and window resizes.
 *
 * <p>Each {@link #open} uses its own short-lived {@link SshClient} so per-connection host-key
 * verification and lifecycle stay isolated. Transport, auth and host-key failures surface as the
 * distinct domain SSH exceptions so the terminal can show the operator a precise reason.
 */
@Component
@Slf4j
public class MinaSshSessionAdapter implements ForOpeningSshSessions {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration CHANNEL_TIMEOUT = Duration.ofSeconds(12);
    private static final String PTY_TYPE = "xterm-256color";
    private static final int INITIAL_COLS = 80;
    private static final int INITIAL_ROWS = 24;

    @Override
    public SshSession open(SshTarget target, SshOutputListener onOutput) {
        SshClient client = SshClient.setUpDefaultClient();
        String[] presented = new String[1];
        AtomicBoolean mismatch = new AtomicBoolean(false);
        // Host-key TOFU: the domain decides trust; the verifier only records the presented fingerprint
        // and rejects a mismatch (which aborts the connect handshake).
        client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
            String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey);
            presented[0] = fingerprint;
            HostKeyTrust trust = HostKeyTrust.evaluate(target.pinnedFingerprint(), fingerprint);
            if (trust == HostKeyTrust.MISMATCH) {
                mismatch.set(true);
                return false;
            }
            return true;
        });
        client.start();

        ClientSession session = connect(client, target, presented, mismatch);
        authenticate(client, session, target, presented, mismatch);
        ChannelShell channel = openShell(client, session, target, onOutput);

        channel.addCloseFutureListener(f -> onOutput.onClosed());
        return new MinaSshSession(client, session, channel, presented[0]);
    }

    private ClientSession connect(SshClient client, SshTarget target, String[] presented, AtomicBoolean mismatch) {
        try {
            return client.connect(target.username(), target.host(), target.port())
                .verify(CONNECT_TIMEOUT).getSession();
        } catch (IOException e) {
            client.stop();
            if (mismatch.get()) {
                throw new HostKeyMismatchException(target.host(), target.pinnedFingerprint(), presented[0]);
            }
            throw new SshConnectException(
                "Could not reach " + target.host() + ":" + target.port() + " (" + rootMessage(e) + ")", e);
        }
    }

    private void authenticate(SshClient client, ClientSession session, SshTarget target,
                              String[] presented, AtomicBoolean mismatch) {
        try {
            if (target.authMethod() == AuthMethod.PASSWORD) {
                session.addPasswordIdentity(target.secret());
            } else {
                for (KeyPair keyPair : loadKeyPairs(target)) {
                    session.addPublicKeyIdentity(keyPair);
                }
            }
            AuthFuture auth = session.auth().verify(AUTH_TIMEOUT);
            if (!auth.isSuccess()) {
                throw new SshAuthException("Authentication failed for " + target.username() + "@" + target.host());
            }
        } catch (SshAuthException e) {
            closeQuietly(session, client);
            // A rejected host key can surface here (MINA validates the server key as auth proceeds)
            // rather than during connect — a mismatch must still read as a host-key error, not auth.
            if (mismatch.get()) {
                throw new HostKeyMismatchException(target.host(), target.pinnedFingerprint(), presented[0]);
            }
            throw e;
        } catch (Exception e) {
            closeQuietly(session, client);
            if (mismatch.get()) {
                throw new HostKeyMismatchException(target.host(), target.pinnedFingerprint(), presented[0]);
            }
            throw new SshAuthException(
                "Authentication failed for " + target.username() + "@" + target.host() + " (" + rootMessage(e) + ")", e);
        }
    }

    private ChannelShell openShell(SshClient client, ClientSession session, SshTarget target,
                                   SshOutputListener onOutput) {
        try {
            ChannelShell channel = session.createShellChannel();
            channel.setPtyType(PTY_TYPE);
            channel.setPtyColumns(INITIAL_COLS);
            channel.setPtyLines(INITIAL_ROWS);
            channel.setOut(new ListenerOutputStream(onOutput));
            channel.setErr(new ListenerOutputStream(onOutput));
            channel.open().verify(CHANNEL_TIMEOUT);
            return channel;
        } catch (IOException e) {
            closeQuietly(session, client);
            throw new SshConnectException("Could not open a shell on " + target.host() + " (" + rootMessage(e) + ")", e);
        }
    }

    private static Collection<KeyPair> loadKeyPairs(SshTarget target) {
        FilePasswordProvider passwordProvider = (target.passphrase() == null || target.passphrase().isBlank())
            ? null : FilePasswordProvider.of(target.passphrase());
        try {
            Collection<KeyPair> keys = SecurityUtils.getKeyPairResourceParser()
                .loadKeyPairs(null, NamedResource.ofName("credential"), passwordProvider, target.secret());
            if (keys == null || keys.isEmpty()) {
                throw new SshAuthException("The stored private key could not be parsed");
            }
            return keys;
        } catch (SshAuthException e) {
            throw e;
        } catch (Exception e) {
            // A bad passphrase or malformed key material — an auth-side problem, not a transport one.
            throw new SshAuthException("The stored private key could not be loaded (" + rootMessage(e) + ")", e);
        }
    }

    private static void closeQuietly(ClientSession session, SshClient client) {
        try {
            if (session != null) session.close(true);
        } catch (Exception ignored) {
            // best effort
        }
        try {
            client.stop();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg != null ? msg : cur.getClass().getSimpleName();
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

    /** Live handle over a MINA shell channel; write/resize/close are best-effort and close is idempotent. */
    private static final class MinaSshSession implements SshSession {
        private final SshClient client;
        private final ClientSession session;
        private final ChannelShell channel;
        private final OutputStream toRemote;
        private final String fingerprint;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        MinaSshSession(SshClient client, ClientSession session, ChannelShell channel, String fingerprint) {
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
